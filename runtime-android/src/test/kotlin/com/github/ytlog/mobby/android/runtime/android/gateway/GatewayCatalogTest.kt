package com.github.ytlog.mobby.android.runtime.android.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class GatewayCatalogTest {
    private class Exchange(val path: String, val headers: Map<String, String>, val socket: Socket)
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
                        try { handler(Exchange(first.split(' ')[1], headers, socket)) }
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
        val headers = "HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        exchange.socket.getOutputStream().use { it.write(headers.toByteArray()); it.write(bytes) }
    }

    @Test fun `responses catalog keeps ids and display names and does not keep the body`() {
        var path = ""
        var bearer: String? = null
        var apiKey: String? = null
        server({ exchange ->
            path = exchange.path
            bearer = exchange.headers["authorization"]
            apiKey = exchange.headers["x-api-key"]
            reply(exchange, 200, """{"data":[{"id":"gpt-test","name":"GPT Test"},{"id":"other","display_name":"Other"}]}""")
        }) { base ->
            val result = runBlocking {
                GatewayCatalog().fetch(GatewayConfig("$base/api/v2", "gpt-test", "synthetic-secret", GatewayProtocol.RESPONSES))
            }
            assertEquals("/api/v2/models", path)
            assertEquals("Bearer synthetic-secret", bearer)
            assertNull(apiKey)
            val ready = result as CatalogResult.Ready
            assertEquals(listOf(GatewayModel("gpt-test", "GPT Test"), GatewayModel("other", "Other")), ready.models)
            assertFalse(result.toString().contains("synthetic-secret"))
        }
    }

    @Test fun `messages catalog pages with the native key and stops when the list ends`() {
        val paths = mutableListOf<String>()
        server({ exchange ->
            paths += exchange.path
            assertEquals("synthetic-secret", exchange.headers["x-api-key"])
            assertEquals("2023-06-01", exchange.headers["anthropic-version"])
            if (exchange.path.contains("after_id=")) reply(exchange, 200, """{"data":[{"id":"claude-b","display_name":"B"}],"has_more":false,"last_id":"claude-b"}""")
            else reply(exchange, 200, """{"data":[{"id":"claude-a","display_name":"A"}],"has_more":true,"last_id":"claude-a"}""")
        }) { base ->
            val result = runBlocking {
                GatewayCatalog().fetch(GatewayConfig(base, "claude-a", "synthetic-secret", GatewayProtocol.MESSAGES))
            } as CatalogResult.Ready
            assertEquals(listOf("/v1/models?limit=100", "/v1/models?limit=100&after_id=claude-a"), paths)
            assertEquals(listOf("claude-a", "claude-b"), result.models.map { it.id })
            assertEquals(listOf("A", "B"), result.models.map { it.name })
        }
    }

    @Test fun `missing catalog and oversized body do not expose the response`() {
        server({ exchange -> reply(exchange, 404, """{"error":"synthetic-secret-body"}""") }) { base ->
            val missing = runBlocking { GatewayCatalog().fetch(GatewayConfig(base, "m", "synthetic-secret")) } as CatalogResult.Unavailable
            assertEquals("该网关没有模型列表接口", missing.message)
            assertFalse(missing.message.contains("synthetic-secret"))
        }
        server({ exchange -> reply(exchange, 200, """{"data":[{"id":"too-big","name":"unused-secret-marker"}]}""") }) { base ->
            val huge = runBlocking {
                GatewayCatalog(maxBytes = 16).fetch(GatewayConfig(base, "m", "synthetic-secret"))
            } as CatalogResult.Unavailable
            assertEquals("模型列表过大，未保存", huge.message)
            assertFalse(huge.message.contains("unused-secret-marker"))
        }
    }

    @Test fun `default model stays first when the catalog omits it`() {
        val merged = mergeCatalog("typed", listOf(GatewayModel("other", "Other")))
        assertEquals(listOf(GatewayModel("typed", "typed"), GatewayModel("other", "Other")), merged)
        assertEquals("Named", mergeCatalog("typed", listOf(GatewayModel("typed", "Named"), GatewayModel("other", "Other"))).first().name)
    }
}
