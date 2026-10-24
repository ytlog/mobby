package com.github.ytlog.mobby.android.programs.base.net

import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.storage.enumType
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class NetLimits(
    val requestBytes: Int = 1024 * 1024,
    val inlineResponseBytes: Int = 1024 * 1024,
    val downloadBytes: Long = 50L * 1024 * 1024,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 30_000,
    val maxRedirects: Int = 5,
) { companion object { val DEFAULT = NetLimits() } }

/** Name resolution and connection opening, replaceable in tests. */
interface NetTransport {
    fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
    fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
    fun blocked(address: InetAddress): Boolean = address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc) ||
        (address is Inet4Address && address.address[0].toInt() and 0xff == 100 && address.address[1].toInt() and 0xc0 == 64)
}

object PlatformNetTransport : NetTransport

/**
 * `net.http`: requests sent by mobby through the Android network stack. Only granted hosts and
 * methods, never loopback or private addresses, every redirect hop rechecked, and secrets filled
 * in only for hosts they are bound to.
 */
class NetHttpCapability(
    private val secrets: SecretStore,
    private val transport: NetTransport = PlatformNetTransport,
    private val limits: NetLimits = NetLimits.DEFAULT,
) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("net.http", 1, CapabilityKind.COMMAND,
        schema(listOf("url"), "url" to stringType(8_192), "method" to enumType("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"),
            "headers" to buildJsonObject { put("type", "object") }, "body" to stringType(limits.requestBytes)),
        buildJsonObject { put("type", "object") }, ScopeKind.DOMAINS, setOf(SideEffect.NETWORK, SideEffect.SEND), emptyList(),
        setOf(Surface.BACKGROUND, Surface.UI), limits.readTimeoutMillis + 15_000L, true, CapabilityLimits(limits.requestBytes + 64 * 1024, 120))

    override fun availability(): Availability = Availability.Available

    override fun callScope(args: JsonObject): CallScope {
        val url = parse(neutral(args.text("url") ?: invalid("url_required")))
        return CallScope(host = url.host.lowercase(), method = method(args), insecureHttp = url.protocol == "http")
    }

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val method = method(call.args)
        val headers = (call.args["headers"] as? JsonObject).orEmpty().mapValues { (name, value) ->
            if (!name.matches(HEADER_NAME) || name.lowercase() in FORBIDDEN_HEADERS) invalid("header_not_allowed: $name")
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 8_192 && '\n' !in it.content && '\r' !in it.content }?.content
                ?: invalid("header_values_must_be_single_line_text")
        }
        val body = call.args.text("body")?.toByteArray()
        if (body != null && method in setOf("GET", "HEAD")) invalid("body_not_allowed_for_$method")
        // The template keeps secret placeholders; only the host-checked copy is parsed until a hop is sent.
        var template = call.args.text("url")!!
        var url = parse(neutral(template))
        var hops = 0
        while (true) {
            val host = url.host.lowercase()
            if (hops > 0 && !call.allows(CallScope(host = host, method = method, insecureHttp = url.protocol == "http")))
                throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "redirect_to_ungranted_host", Effect.UNKNOWN)
            checkAddresses(host)
            // Secrets are filled per hop, so a redirect to another host never receives them.
            val target = parse(fill(template, call.context.programId, host, encode = true))
            val hopHeaders = headers.mapValues { fill(it.value, call.context.programId, host, encode = false) }
            val response = runInterruptible(Dispatchers.IO) { exchange(target, method, hopHeaders, body, call) }
            if (response.status in 300..399 && response.location != null) {
                if (++hops > limits.maxRedirects) throw CapabilityException(ProgramErrorCode.FAILED, "too_many_redirects", Effect.UNKNOWN)
                template = URL(target, response.location).toString()
                if (SECRET.containsMatchIn(template)) invalid("redirect_with_placeholder")
                url = parse(template)
                continue
            }
            return CapabilityOutcome(Effect.CONFIRMED, response.json, OperationAudit(host, method, response.status, body?.size?.toLong() ?: 0, response.bytes))
        }
    }

    private class Response(val status: Int, val location: String?, val json: JsonObject, val bytes: Long)

    private fun exchange(url: URL, method: String, headers: Map<String, String>, body: ByteArray?, call: CapabilityCall): Response {
        val connection = transport.open(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = limits.connectTimeoutMillis
            connection.readTimeout = limits.readTimeoutMillis
            connection.requestMethod = method
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val collected = java.io.ByteArrayOutputStream()
            var file: File? = null
            var output: java.io.OutputStream = collected
            var total = 0L
            stream?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (call.isCancelled()) throw CapabilityException(ProgramErrorCode.CANCELLED, "cancelled", Effect.UNKNOWN)
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limits.downloadBytes) throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "response_too_large", Effect.CONFIRMED)
                    if (file == null && total > limits.inlineResponseBytes) {
                        file = File(call.space.temp.apply { mkdirs() }, "http-${UUID.randomUUID()}")
                        output = file!!.outputStream().also { it.write(collected.toByteArray()) }
                    }
                    output.write(buffer, 0, n)
                }
            }
            if (output !== collected) output.close()
            val text = if (file == null) utf8(collected.toByteArray()) else null
            if (file == null && text == null) {
                file = File(call.space.temp.apply { mkdirs() }, "http-${UUID.randomUUID()}").also { it.writeBytes(collected.toByteArray()) }
            }
            val json = buildJsonObject {
                put("status", status)
                put("headers", buildJsonObject {
                    connection.headerFields.filterKeys { it != null }.forEach { (name, values) -> put(name.lowercase(), values.joinToString(", ")) }
                })
                text?.let { put("body", it) }
                file?.let { put("file", "tmp/${it.name}"); put("sizeBytes", total) }
            }
            return Response(status, connection.getHeaderField("Location"), json, total)
        } catch (error: SocketTimeoutException) {
            throw CapabilityException(ProgramErrorCode.TIMEOUT, "network_timeout", Effect.UNKNOWN)
        } catch (error: IOException) {
            throw CapabilityException(ProgramErrorCode.SOURCE_DISCONNECTED, "network_error: ${error.javaClass.simpleName}", Effect.UNKNOWN)
        } finally { connection.disconnect() }
    }

    private fun checkAddresses(host: String) {
        val addresses = try { transport.resolve(host) } catch (_: UnknownHostException) {
            throw CapabilityException(ProgramErrorCode.SOURCE_DISCONNECTED, "host_not_resolved")
        }
        if (addresses.isEmpty() || addresses.any(transport::blocked)) throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "address_not_allowed")
    }

    private suspend fun fill(text: String, programId: String, host: String, encode: Boolean): String {
        var result = text
        for (match in SECRET.findAll(text).toList()) {
            val value = secrets.reveal(programId, match.groupValues[1], host)
            result = result.replace(match.value, if (encode) URLEncoder.encode(value, "UTF-8") else value)
        }
        return result
    }

    private fun method(args: JsonObject) = args.text("method") ?: "GET"

    /** Parsable stand-in for a URL template; a placeholder may not decide the host. */
    private fun neutral(template: String): String {
        val replaced = SECRET.replace(template, "secret")
        val host = runCatching { URI(replaced).host }.getOrNull()
        if (host != null && SECRET.containsMatchIn(template.substringAfter("://").substringBefore('/'))) invalid("placeholder_in_host")
        return replaced
    }

    private fun parse(text: String): URL {
        val url = try { URI(text).toURL() } catch (_: Exception) { invalid("invalid_url") }
        if (url.protocol != "https" && url.protocol != "http") invalid("only_http_and_https")
        if (url.host.isNullOrEmpty() || url.userInfo != null) invalid("url_needs_a_host_without_credentials")
        return url
    }

    private fun utf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())

    companion object {
        private val SECRET = Regex("\\{\\{secret:([A-Za-z][A-Za-z0-9_-]{0,63})\\}\\}")
        private val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade", "proxy-authorization", "te", "trailer")
    }
}
