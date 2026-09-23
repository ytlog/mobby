package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.Executors

class GatewayProbeTest {
    private val ref = GatewayProfileRef("CODEX", 7)
    private class Exchange(val path: String, val headers: Map<String, String>, val body: String, val socket: Socket) {
        val responseHeaders = mutableMapOf<String, String>()
    }
    private fun server(handler: (Exchange) -> Unit, block: (String) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                executor.execute {
                    socket.use {
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val first = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val body = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var read = 0
                        while (read < body.size) { val n = input.read(body, read, body.size - read); if (n < 0) break; read += n }
                        try { handler(Exchange(first.split(' ')[1], headers, body.concatToString(), socket)) }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
            }
        }
        try { block("http://127.0.0.1:${server.localPort}") }
        finally { server.close(); executor.shutdownNow() }
    }
    private fun reply(exchange: Exchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        val headers = "HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
            exchange.responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        exchange.socket.getOutputStream().use { it.write(headers.toByteArray()); it.write(bytes) }
    }
    @Test fun `native protocols send bounded requests with normalized paths and correct auth`() {
        for (protocol in GatewayProtocol.values()) {
            var path = ""; var bearer: String? = null; var auth: String? = null; var version: String? = null; var body = ""
            val response = when (protocol) {
                GatewayProtocol.RESPONSES -> """{"object":"response","status":"completed","output":[]}"""
                GatewayProtocol.MESSAGES -> """{"type":"message","role":"assistant","content":[{"type":"text","text":"OK"}],"stop_reason":"end_turn"}"""
            }
            server({ e ->
                path = e.path; bearer = e.headers["authorization"]
                auth = e.headers[if (protocol == GatewayProtocol.MESSAGES) "x-api-key" else "authorization"]
                version = e.headers["anthropic-version"]
                body = e.body
                reply(e, 200, response)
            }) { base ->
                for (suffix in listOf("", "/v1/", "/v1/responses", "/api/v2/messages")) {
                    val check = runBlocking { GatewayProbe().check(ref, GatewayConfig(base + suffix, "test-model", "synthetic-secret", protocol)) }
                    assertEquals(GatewayCheckOutcome.SUCCEEDED, check.outcome); assertEquals(ref, check.profile)
                    val ending = when (protocol) { GatewayProtocol.RESPONSES -> "responses"; GatewayProtocol.MESSAGES -> "messages" }
                    assertEquals((if (suffix.startsWith("/api")) "/api/v2/" else "/v1/") + ending, path)
                    assertEquals("Bearer synthetic-secret", bearer)
                    assertEquals(if (protocol == GatewayProtocol.MESSAGES) "synthetic-secret" else "Bearer synthetic-secret", auth)
                    if (protocol == GatewayProtocol.MESSAGES) assertEquals("2023-06-01", version)
                    val json = Json.parseToJsonElement(body).jsonObject
                    assertEquals("test-model", json["model"]!!.jsonPrimitive.content)
                    assertFalse(json["stream"]!!.jsonPrimitive.boolean)
                    assertEquals(16, json[if (protocol == GatewayProtocol.RESPONSES) "max_output_tokens" else "max_tokens"]!!.jsonPrimitive.int)
                    assertFalse(check.toString().contains("synthetic-secret"))
                }
            }
        }
    }
    @Test fun `redirect and HTTP rejection are not followed or reported as success`() {
        for (code in listOf(302, 401, 403, 404, 429, 500)) {
            var requests = 0
            server({ e -> requests++; e.responseHeaders["Location"] = "/secret-destination"; reply(e, code, "sensitive upstream body") }) { base ->
                val check = runBlocking { GatewayProbe().check(ref, GatewayConfig(base, "m", "test-secret")) }
                assertEquals(GatewayCheckOutcome.HTTP_ERROR, check.outcome); assertEquals(code, check.httpStatus)
                assertEquals(1, requests); assertFalse(check.toString().contains("sensitive"))
            }
        }
    }
    @Test fun `HTTP 200 with HTML error or unfinished response is not success`() {
        for (body in listOf("<html>login</html>", "{}", """{"error":{"message":"private"}}""", """{"object":"response","status":"failed","output":[]}""")) {
            server({ reply(it, 200, body) }) { base ->
                val check = runBlocking { GatewayProbe().check(ref, GatewayConfig(base, "m")) }
                assertEquals(GatewayCheckOutcome.INVALID_RESPONSE, check.outcome)
            }
        }
    }
    @Test fun `oversized response is bounded`() {
        server({ reply(it, 200, "x".repeat(70_000)) }) { base ->
            assertEquals(GatewayCheckOutcome.RESPONSE_TOO_LARGE, runBlocking { GatewayProbe().check(ref, GatewayConfig(base, "m")) }.outcome)
        }
    }
    @Test fun `slow response reports timeout and cancelled checks cannot report success`() {
        val received = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server({ e -> if (requests.incrementAndGet() == 2) received.complete(Unit); Thread.sleep(500); runCatching { reply(e, 200, "{}") } }) { base ->
            runBlocking {
                assertEquals(GatewayCheckOutcome.TIMEOUT, GatewayProbe(100).check(ref, GatewayConfig(base, "m")).outcome)
                val job = async { GatewayProbe(100).check(ref, GatewayConfig(base, "m")) }
                received.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
            }
        }
    }
    @Test fun `transport failures are classified without exposing exception text`() {
        val failures = listOf(
            java.net.UnknownHostException("synthetic-secret") to GatewayCheckOutcome.DNS_ERROR,
            javax.net.ssl.SSLHandshakeException("synthetic-secret") to GatewayCheckOutcome.TLS_ERROR,
            java.net.SocketTimeoutException("synthetic-secret") to GatewayCheckOutcome.TIMEOUT,
            java.net.ConnectException("synthetic-secret") to GatewayCheckOutcome.CONNECTION_ERROR)
        for ((error, expected) in failures) {
            val result = runBlocking { GatewayProbe(open = { throw error }).check(ref, GatewayConfig("https://test.invalid", "m", "synthetic-secret")) }
            assertEquals(expected, result.outcome); assertFalse(result.toString().contains("synthetic-secret"))
        }
    }

    @Test fun `output limits and incomplete responses are not reported as completed checks`() {
        val responses = listOf(
            GatewayProtocol.RESPONSES to """{"object":"response","status":"incomplete","output":[]}""",
            GatewayProtocol.MESSAGES to """{"type":"message","role":"assistant","content":[],"stop_reason":"max_tokens"}""")
        for ((protocol, body) in responses) server({ reply(it, 200, body) }) { base ->
            assertEquals(GatewayCheckOutcome.INCOMPLETE_RESPONSE, runBlocking { GatewayProbe().check(ref, GatewayConfig(base, "m", protocol = protocol)) }.outcome)
        }
    }

}
