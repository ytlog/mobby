package com.github.ytlog.mobby.android.runtime.android.gateway

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A configured gateway as programs may choose it: no endpoint path or key, only what the user needs to pick. */
data class TextGateway(val id: String, val label: String, val models: List<String>)
data class TextTurn(val role: String, val text: String)
data class TextReply(val text: String, val inputTokens: Long?, val outputTokens: Long?)

/** [reached] is true when the request may have been processed upstream, so the caller cannot treat it as never sent. */
class TextModelFailure(val reason: String, val reached: Boolean) : RuntimeException(reason)

/**
 * Text-only, non-streaming model calls for programs, in the gateway's own protocol (Responses first, else Messages).
 * Like [GatewayProbe], request and response bodies and exception messages never leave this class except as the reply text.
 */
class GatewayTextModels internal constructor(
    private val records: () -> List<GatewayRecord>,
    private val defaultChoice: () -> GatewayChoice?,
    private val timeoutMillis: Int = 120_000,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    constructor(context: Context) : this(GatewayStore(context.applicationContext))
    private constructor(store: GatewayStore) : this(store::list, store::default)

    fun gateways(): List<TextGateway> = records().map { record ->
        val host = record.routes.values.firstNotNullOfOrNull { runCatching { URL(it).host }.getOrNull() } ?: record.id.take(8)
        TextGateway(record.id, host, (listOf(record.model) + record.models.map { it.id }).filter { it.isNotBlank() }.distinct())
    }

    fun preferred(): Pair<String, String>? = defaultChoice()?.let { choice ->
        records().firstOrNull { it.id == choice.id }?.let { choice.id to it.model }
    }

    suspend fun complete(gatewayId: String, model: String, system: String?, turns: List<TextTurn>, maxOutputTokens: Int): TextReply {
        val record = records().firstOrNull { it.id == gatewayId } ?: throw TextModelFailure("gateway_missing", false)
        if (model != record.model && record.models.none { it.id == model }) throw TextModelFailure("model_not_offered", false)
        val protocol = listOf(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES).firstOrNull { it in record.routes }
            ?: throw TextModelFailure("gateway_protocol_unsupported", false)
        return request(protocol, record.routes.getValue(protocol), record.key, body(protocol, model, system, turns, maxOutputTokens))
    }

    private fun body(protocol: GatewayProtocol, model: String, system: String?, turns: List<TextTurn>, max: Int) = buildJsonObject {
        put("model", model); put("stream", false)
        when (protocol) {
            GatewayProtocol.RESPONSES -> {
                put("store", false); put("max_output_tokens", max)
                system?.let { put("instructions", it) }
                putJsonArray("input") { turns.forEach { turn -> addJsonObject {
                    put("role", turn.role)
                    putJsonArray("content") { addJsonObject {
                        put("type", if (turn.role == "assistant") "output_text" else "input_text"); put("text", turn.text)
                    } }
                } } }
            }
            GatewayProtocol.MESSAGES -> {
                put("max_tokens", max)
                system?.let { put("system", it) }
                putJsonArray("messages") { turns.forEach { turn -> addJsonObject { put("role", turn.role); put("content", turn.text) } } }
            }
        }
    }.toString().toByteArray(Charsets.UTF_8)

    private suspend fun request(protocol: GatewayProtocol, endpoint: String, key: String, body: ByteArray): TextReply = runInterruptible(Dispatchers.IO) {
        val path = if (protocol == GatewayProtocol.RESPONSES) "/responses" else "/messages"
        var connection: HttpURLConnection? = null
        var sent = false
        try {
            val http = open(GatewayEndpoint.url(endpoint, path)).also { connection = it }
            http.instanceFollowRedirects = false
            http.connectTimeout = 15_000; http.readTimeout = timeoutMillis
            http.requestMethod = "POST"; http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Accept", "application/json")
            if (key.isNotEmpty()) http.setRequestProperty("Authorization", "Bearer $key")
            if (protocol == GatewayProtocol.MESSAGES) {
                http.setRequestProperty("anthropic-version", "2023-06-01")
                if (key.isNotEmpty()) http.setRequestProperty("x-api-key", key)
            }
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            sent = true
            val status = http.responseCode
            // A 4xx is a refusal before any work; a 5xx may come after the model already ran.
            if (status !in 200..299) throw TextModelFailure("gateway_http_$status", status >= 500)
            val bytes = http.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (out.size() + count > MAX_RESPONSE) throw TextModelFailure("gateway_response_too_large", true)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val parsed = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            parse(protocol, parsed) ?: throw TextModelFailure("gateway_response_invalid", true)
        } catch (failure: TextModelFailure) { throw failure }
        catch (_: UnknownHostException) { throw TextModelFailure("gateway_dns_error", false) }
        catch (_: SSLException) { throw TextModelFailure("gateway_tls_error", sent) }
        catch (_: SocketTimeoutException) { throw TextModelFailure("gateway_timeout", sent) }
        catch (_: IOException) { throw TextModelFailure("gateway_connection_error", sent) }
        finally { connection?.disconnect() }
    }

    internal fun parse(protocol: GatewayProtocol, value: JsonObject?): TextReply? = runCatching {
        if (value == null || value["error"]?.let { it != JsonNull } == true) return null
        val usage = value["usage"] as? JsonObject
        fun tokens(name: String) = (usage?.get(name) as? JsonPrimitive)?.longOrNull
        val text = when (protocol) {
            GatewayProtocol.RESPONSES -> (value["output"] as? JsonArray ?: return null).mapNotNull { it as? JsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }.mapNotNull { it as? JsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "output_text" }.joinToString("") { it.getValue("text").jsonPrimitive.content }
            GatewayProtocol.MESSAGES -> (value["content"] as? JsonArray ?: return null).mapNotNull { it as? JsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "text" }.joinToString("") { it.getValue("text").jsonPrimitive.content }
        }
        TextReply(text, tokens("input_tokens"), tokens("output_tokens"))
    }.getOrNull()

    private companion object { const val MAX_RESPONSE = 4 * 1024 * 1024 }
}
