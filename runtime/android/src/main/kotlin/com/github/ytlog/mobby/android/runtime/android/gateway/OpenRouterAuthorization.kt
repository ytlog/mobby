package com.github.ytlog.mobby.android.runtime.android.gateway

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resumeWithException

enum class GatewayAuthorizationFailure { TIMEOUT, INVALID_CALLBACK, HTTP_ERROR, INVALID_RESPONSE, CONNECTION_ERROR }
class GatewayAuthorizationException(val failure: GatewayAuthorizationFailure) : Exception(failure.name)

/** Official PKCE flow. No account, code, verifier, response body or key is logged or persisted. */
class OpenRouterAuthorization(
    private val timeoutMillis: Int = 5 * 60_000,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun connect(openBrowser: suspend (String) -> Unit): CharArray {
        var issued: CharArray? = null
        try {
            return withTimeout(timeoutMillis.toLong()) {
                withContext(Dispatchers.IO) {
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                        server.soTimeout = timeoutMillis
                        val verifier = randomToken()
                        val state = randomToken()
                        val callback = "http://127.0.0.1:${server.localPort}/callback"
                        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
                        val url = "https://openrouter.ai/auth?callback_url=${quote(callback)}&code_challenge=$challenge" +
                            "&code_challenge_method=S256&state=$state&key_label=mobby"
                        openBrowser(url)
                        val code = blocking { resources ->
                            resources.add { server.close() }
                            server.accept().use { socket ->
                                resources.add { socket.close() }
                                socket.soTimeout = minOf(timeoutMillis, 5_000)
                                val input = socket.getInputStream()
                                val request = line(input, 4_096).split(' ')
                                val headers = linkedMapOf<String, String>()
                                var total = 0
                                while (true) {
                                    val header = line(input, 1_024)
                                    total += header.length
                                    requireCallback(total <= 8_192)
                                    if (header.isEmpty()) break
                                    val name = header.substringBefore(':').lowercase()
                                    requireCallback(':' in header && name !in headers)
                                    headers[name] = header.substringAfter(':').trim()
                                }
                                val valid = runCatching {
                                    requireCallback(request.size == 3 && request[0] == "GET" && request[2] in listOf("HTTP/1.1", "HTTP/1.0"))
                                    requireCallback(headers["host"] == "127.0.0.1:${server.localPort}" && "transfer-encoding" !in headers &&
                                        headers["content-length"].let { it == null || it == "0" })
                                    callbackCode(request[1], state)
                                }
                                val text = if (valid.isSuccess) "Authorization received. Return to mobby to finish setup."
                                    else "Authorization was not accepted. Return to mobby and try again."
                                val bytes = text.toByteArray(Charsets.UTF_8)
                                socket.getOutputStream().apply {
                                    write(("HTTP/1.1 ${if (valid.isSuccess) "200 OK" else "400 Bad Request"}\r\n" +
                                        "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                                    write(bytes); flush()
                                }
                                valid.getOrThrow()
                            }
                        }
                        exchange(code, verifier).also { issued = it }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            issued?.fill('\u0000')
            currentCoroutineContext().ensureActive()
            throw GatewayAuthorizationException(GatewayAuthorizationFailure.TIMEOUT)
        } catch (e: CancellationException) { issued?.fill('\u0000'); throw e }
        catch (e: GatewayAuthorizationException) { issued?.fill('\u0000'); throw e }
        catch (_: SocketTimeoutException) { issued?.fill('\u0000'); throw GatewayAuthorizationException(GatewayAuthorizationFailure.TIMEOUT) }
        catch (_: Exception) { issued?.fill('\u0000'); throw GatewayAuthorizationException(GatewayAuthorizationFailure.CONNECTION_ERROR) }
    }

    private suspend fun exchange(code: String, verifier: String): CharArray = blocking { resources ->
        val http = open(URL("https://openrouter.ai/api/v1/auth/keys"))
        resources.add { http.disconnect() }
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = minOf(timeoutMillis, 15_000)
            http.readTimeout = minOf(timeoutMillis, 15_000)
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Accept", "application/json")
            val body = buildJsonObject { put("code", code); put("code_verifier", verifier); put("code_challenge_method", "S256") }.toString().toByteArray(Charsets.UTF_8)
            http.setFixedLengthStreamingMode(body.size)
            try { http.outputStream.use { it.write(body) } } finally { body.fill(0) }
            if (http.responseCode !in 200..299) throw GatewayAuthorizationException(GatewayAuthorizationFailure.HTTP_ERROR)
            val bytes = ByteArrayOutputStream()
            val deadline = System.nanoTime() + minOf(timeoutMillis, 15_000) * 1_000_000L
            http.inputStream.use { input ->
                val buffer = ByteArray(4_096)
                try {
                    while (true) {
                        if (System.nanoTime() > deadline) throw SocketTimeoutException()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (bytes.size() + count > 16_384) throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_RESPONSE)
                        bytes.write(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
            val response = bytes.toByteArray()
            try {
                val root = Json.parseToJsonElement(response.toString(Charsets.UTF_8)) as? JsonObject
                    ?: throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_RESPONSE)
                val key = root["key"] as? JsonPrimitive
                if (root["error"]?.let { it != JsonNull } == true || key == null || !key.isString || key.content.isBlank() ||
                    key.content.length > 8_192 || key.content.any { it.isWhitespace() || it.isISOControl() }) {
                    throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_RESPONSE)
                }
                key.content.toCharArray()
            } catch (e: GatewayAuthorizationException) { throw e }
            catch (_: Exception) { throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_RESPONSE) }
            finally { response.fill(0); bytes.reset() }
        } finally { http.disconnect() }
    }

    private class Resources : Closeable {
        private var closed = false
        private val close = mutableListOf<() -> Unit>()
        @Synchronized fun add(action: () -> Unit) { if (closed) runCatching(action) else close += action }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            close.asReversed().forEach { runCatching(it) }
            close.clear()
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun <T> blocking(action: (Resources) -> T): T = suspendCancellableCoroutine { continuation ->
        val resources = Resources()
        continuation.invokeOnCancellation { resources.close() }
        try {
            val result = action(resources)
            continuation.resume(result) { _, value, _ -> if (value is CharArray) value.fill('\u0000') }
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        finally { resources.close() }
    }
    companion object {
        private fun randomToken(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun quote(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        private fun requireCallback(ok: Boolean) { if (!ok) throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_CALLBACK) }
        internal fun callbackCode(target: String, state: String): String {
            val uri = try { URI(target) } catch (_: Exception) { throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_CALLBACK) }
            requireCallback(uri.scheme == null && uri.host == null && uri.fragment == null && uri.path == "/callback")
            val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
                val name = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name())
                val value = URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8.name())
                name to value
            }
            requireCallback(pairs.map { it.first }.distinct().size == pairs.size)
            val query = pairs.toMap()
            requireCallback(query["state"] == state && query["error"] == null)
            return query["code"]?.takeIf { it.isNotBlank() && it.length <= 2_048 && it.none(Char::isISOControl) }
                ?: throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_CALLBACK)
        }
        private fun line(input: InputStream, limit: Int): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= limit) {
                val value = input.read()
                if (value == -1) throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_CALLBACK)
                if (value == 10) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
                bytes.write(value)
            }
            throw GatewayAuthorizationException(GatewayAuthorizationFailure.INVALID_CALLBACK)
        }
    }
}
