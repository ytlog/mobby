package com.github.ytlog.mobby.android.runtime.android.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Saved model ids and display names. Response bodies are never kept or logged. */
internal sealed interface CatalogResult {
    data class Ready(val models: List<GatewayModel>) : CatalogResult
    data class Unavailable(val message: String) : CatalogResult
}

internal class GatewayCatalog(
    private val timeoutMillis: Int = 15_000,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxModels: Int = 2_000,
    private val open: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun fetch(config: GatewayConfig): CatalogResult = runInterruptible(Dispatchers.IO) {
        config.validate()
        val collected = linkedMapOf<String, GatewayModel>()
        var after: String? = null
        var page = 0
        while (page < 20 && collected.size < maxModels) {
            page++
            when (val loaded = page(config, path(config.protocol, after))) {
                is Page.Failed -> return@runInterruptible if (collected.isEmpty()) CatalogResult.Unavailable(loaded.message)
                    else CatalogResult.Ready(collected.values.toList())
                is Page.Ok -> {
                    loaded.models.forEach { if (collected.size < maxModels) collected.putIfAbsent(it.id, it) }
                    if (!loaded.hasMore || loaded.next.isNullOrBlank() || loaded.next == after) break
                    after = loaded.next
                }
            }
        }
        if (collected.isEmpty()) CatalogResult.Unavailable("模型列表为空") else CatalogResult.Ready(collected.values.toList())
    }

    private fun path(protocol: GatewayProtocol, after: String?): String {
        if (after == null && protocol != GatewayProtocol.MESSAGES) return "/models"
        val cursor = after?.let { "&after_id=${java.net.URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
        return "/models?limit=100$cursor"
    }

    private fun page(config: GatewayConfig, path: String): Page {
        var connection: HttpURLConnection? = null
        try {
            val http = open(GatewayEndpoint.url(config.endpoint, path)).also { connection = it }
            http.instanceFollowRedirects = false
            http.connectTimeout = timeoutMillis
            http.readTimeout = timeoutMillis
            http.requestMethod = "GET"
            http.setRequestProperty("Accept", "application/json")
            if (config.key.isNotEmpty()) http.setRequestProperty("Authorization", "Bearer ${config.key}")
            if (config.protocol == GatewayProtocol.MESSAGES) {
                http.setRequestProperty("anthropic-version", "2023-06-01")
                if (config.key.isNotEmpty()) http.setRequestProperty("x-api-key", config.key)
            }
            val status = http.responseCode
            if (status !in 200..299) return Page.Failed(httpMessage(status))
            val bytes = readLimited(http) ?: return Page.Failed("模型列表过大，未保存")
            return parse(bytes.toString(Charsets.UTF_8))
        } catch (e: InterruptedException) { throw e }
        catch (_: UnknownHostException) { return Page.Failed("无法解析网关域名，模型列表未保存") }
        catch (_: SSLException) { return Page.Failed("TLS 校验失败，模型列表未保存") }
        catch (_: SocketTimeoutException) { return Page.Failed("读取模型列表超时，未保存") }
        catch (_: IOException) { return Page.Failed("无法读取模型列表，未保存") }
        finally { connection?.disconnect() }
    }

    private fun readLimited(http: HttpURLConnection): ByteArray? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val bytes = java.io.ByteArrayOutputStream()
        http.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (System.nanoTime() > deadline) throw SocketTimeoutException()
                val count = input.read(buffer)
                if (count == -1) break
                if (bytes.size() + count > maxBytes) return null
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toByteArray()
    }

    private fun parse(text: String): Page = runCatching {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return Page.Failed("模型列表格式无法识别")
        if (root["error"]?.let { it != JsonNull } == true) return Page.Failed("模型列表格式无法识别")
        val data = root["data"] as? JsonArray ?: return Page.Failed("模型列表格式无法识别")
        val models = data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
            if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() }) return@mapNotNull null
            val name = (item["name"] ?: item["display_name"])?.jsonPrimitive?.takeIf { it.isString }?.content?.take(120) ?: id
            if (name.any { it.isISOControl() }) return@mapNotNull null
            GatewayModel(id, name.ifBlank { id })
        }
        val next = root["last_id"]?.jsonPrimitive?.takeIf { it.isString }?.content
        Page.Ok(models, root["has_more"]?.jsonPrimitive?.booleanOrNull == true, next)
    }.getOrDefault(Page.Failed("模型列表格式无法识别"))

    private fun httpMessage(status: Int) = when (status) {
        401, 403 -> "模型列表鉴权失败（HTTP $status），请检查密钥"
        404 -> "该网关没有模型列表接口"
        in 300..399 -> "模型列表要求重定向（HTTP $status），未转发凭据"
        else -> "模型列表请求被拒绝（HTTP $status）"
    }

    private sealed interface Page {
        data class Ok(val models: List<GatewayModel>, val hasMore: Boolean, val next: String?) : Page
        data class Failed(val message: String) : Page
    }
}
