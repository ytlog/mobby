package com.github.ytlog.mobby.android.runtime.android.gateway

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class GatewayTextModelsTest {
    private class Seen(var path: String = "", var authorization: String? = null, var body: JsonObject? = null)

    /** A one-route loopback gateway that answers every request with [status] and [response]. */
    private fun gateway(status: Int, response: String, block: (String, Seen) -> Unit) {
        val seen = Seen()
        val executor = Executors.newCachedThreadPool()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    // Content-Length counts bytes, so the body is read as bytes, not characters.
                    val input = socket.getInputStream().buffered()
                    fun line() = generateSequence { input.read().takeIf { it != -1 && it != '\n'.code } }
                        .filter { it != '\r'.code }.map { it.toByte() }.toList().toByteArray().toString(Charsets.UTF_8)
                    seen.path = line().split(' ')[1]
                    val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    seen.authorization = headers["authorization"]
                    val body = input.readNBytes(headers.getValue("content-length").toInt())
                    seen.body = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                    val bytes = response.toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
        try { block("http://127.0.0.1:${server.localPort}/v1", seen) } finally { server.close(); executor.shutdownNow() }
    }

    private fun models(protocol: GatewayProtocol, base: String): Pair<GatewayTextModels, String> {
        val id = "0b6f4c2e-1a2b-4c3d-8e9f-001122334455"
        val record = GatewayRecord(id, 1, mapOf(protocol to base), "default-model", "synthetic-test-key", listOf(GatewayModel("other-model", "Other")))
        return GatewayTextModels({ listOf(record) }, { null }) to id
    }

    @Test fun `responses gateways get instructions, typed input and the output limit`() =
        gateway(200, """{"object":"response","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"一句话总结"}]}],
            "usage":{"input_tokens":40,"output_tokens":8}}""") { base, seen ->
            val (models, id) = models(GatewayProtocol.RESPONSES, base)
            val reply = runBlocking { models.complete(id, "other-model", "简短", listOf(TextTurn("user", "总结"), TextTurn("assistant", "好"), TextTurn("user", "再短点")), 300) }
            assertEquals(TextReply("一句话总结", 40, 8), reply)
            assertEquals("/v1/responses", seen.path)
            assertEquals("Bearer synthetic-test-key", seen.authorization)
            val body = seen.body!!
            assertEquals("other-model", body.getValue("model").jsonPrimitive.content)
            assertEquals("简短", body.getValue("instructions").jsonPrimitive.content)
            assertEquals(300, body.getValue("max_output_tokens").jsonPrimitive.int)
            assertFalse(body.getValue("stream").jsonPrimitive.boolean)
            assertEquals(listOf("input_text", "output_text", "input_text"),
                body.getValue("input").jsonArray.map { it.jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(listOf(TextGateway(id, "127.0.0.1", listOf("default-model", "other-model"))), models.gateways())
        }

    @Test fun `messages gateways get a system prompt and max tokens`() =
        gateway(200, """{"type":"message","role":"assistant","content":[{"type":"text","text":"好的"}]}""") { base, seen ->
            val (models, id) = models(GatewayProtocol.MESSAGES, base)
            assertEquals(TextReply("好的", null, null), runBlocking { models.complete(id, "default-model", "简短", listOf(TextTurn("user", "你好")), 64) })
            assertEquals("/v1/messages", seen.path)
            val body = seen.body!!
            assertEquals("简短", body.getValue("system").jsonPrimitive.content)
            assertEquals(64, body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals("你好", body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
        }

    @Test fun `refusals, server errors and unknown models are told apart`() {
        gateway(429, """{"error":{"message":"slow down, key synthetic-test-key"}}""") { base, _ ->
            val (models, id) = models(GatewayProtocol.RESPONSES, base)
            val failure = runCatching { runBlocking { models.complete(id, "default-model", null, listOf(TextTurn("user", "x")), 10) } }.exceptionOrNull() as TextModelFailure
            assertEquals("gateway_http_429" to false, failure.reason to failure.reached)
            val unknown = runCatching { runBlocking { models.complete(id, "missing", null, listOf(TextTurn("user", "x")), 10) } }.exceptionOrNull() as TextModelFailure
            assertEquals("model_not_offered", unknown.reason)
        }
        gateway(502, "{}") { base, _ ->
            val (models, id) = models(GatewayProtocol.RESPONSES, base)
            val failure = runCatching { runBlocking { models.complete(id, "default-model", null, listOf(TextTurn("user", "x")), 10) } }.exceptionOrNull() as TextModelFailure
            assertEquals("gateway_http_502" to true, failure.reason to failure.reached)
        }
    }
}
