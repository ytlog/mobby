package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.GatewayCheck
import com.github.ytlog.mobby.android.runtime.api.GatewayCheckOutcome
import com.github.ytlog.mobby.android.runtime.api.GatewayProfileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import javax.net.ssl.SSLException

/** Bounded, non-streaming protocol check. Never returns/logs upstream bodies or exception messages. */
internal class GatewayProbe(private val timeoutMillis: Int = 10_000,
    private val open: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun check(ref: GatewayProfileRef, config: GatewayConfig): GatewayCheck = runInterruptible(Dispatchers.IO) {
        config.validate()
        val path = when (config.protocol) {
            GatewayProtocol.RESPONSES -> "/responses"
            GatewayProtocol.MESSAGES -> "/messages"
        }
        val body = buildJsonObject {
            put("model", config.model); put("stream", false)
            if (config.protocol == GatewayProtocol.RESPONSES) {
                put("input", "Reply OK."); put("max_output_tokens", 16); put("store", false)
            } else {
                putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "Reply OK.") } }
                put("max_tokens", 16)
            }
        }.toString().toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        var status: Int? = null
        fun result(outcome: GatewayCheckOutcome) = GatewayCheck(ref, outcome, status)
        try {
            val http = open(GatewayEndpoint.url(config.endpoint, path)).also { connection = it }
            http.instanceFollowRedirects = false
            http.connectTimeout = timeoutMillis; http.readTimeout = timeoutMillis
            http.requestMethod = "POST"; http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Accept", "application/json")
            if (config.key.isNotEmpty()) http.setRequestProperty("Authorization", "Bearer ${config.key}")
            if (config.protocol == GatewayProtocol.MESSAGES) {
                http.setRequestProperty("anthropic-version", "2023-06-01")
                if (config.key.isNotEmpty()) http.setRequestProperty("x-api-key", config.key)
            }
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            status = http.responseCode
            if (status !in 200..299) return@runInterruptible result(GatewayCheckOutcome.HTTP_ERROR)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            val bytes = java.io.ByteArrayOutputStream()
            http.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (System.nanoTime() > deadline) throw SocketTimeoutException()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > 65_536) return@runInterruptible result(GatewayCheckOutcome.RESPONSE_TOO_LARGE)
                    bytes.write(buffer, 0, count)
                }
            }
            val parsed = runCatching { Json.parseToJsonElement(bytes.toString("UTF-8")) as? JsonObject }.getOrNull()
            result(when {
                !valid(config.protocol, parsed) -> GatewayCheckOutcome.INVALID_RESPONSE
                incomplete(config.protocol, requireNotNull(parsed)) -> GatewayCheckOutcome.INCOMPLETE_RESPONSE
                else -> GatewayCheckOutcome.SUCCEEDED
            })
        } catch (_: UnknownHostException) { result(GatewayCheckOutcome.DNS_ERROR) }
        catch (_: SSLException) { result(GatewayCheckOutcome.TLS_ERROR) }
        catch (_: SocketTimeoutException) { result(GatewayCheckOutcome.TIMEOUT) }
        catch (_: IOException) { result(GatewayCheckOutcome.CONNECTION_ERROR) }
        finally { connection?.disconnect() }
    }

    private fun incomplete(protocol: GatewayProtocol, value: JsonObject): Boolean = when (protocol) {
        GatewayProtocol.RESPONSES -> value["status"]?.jsonPrimitive?.content != "completed"
        GatewayProtocol.MESSAGES -> value["stop_reason"]?.jsonPrimitive?.content !in setOf("end_turn", "stop_sequence")
    }

    private fun valid(protocol: GatewayProtocol, value: JsonObject?): Boolean = runCatching {
        if (value == null || value["error"]?.let { it != JsonNull } == true) return false
        when (protocol) {
            GatewayProtocol.RESPONSES -> value["object"]?.jsonPrimitive?.content == "response" &&
                value["status"]?.jsonPrimitive?.content in setOf("completed", "incomplete") && value["output"] is JsonArray
            GatewayProtocol.MESSAGES -> value["type"]?.jsonPrimitive?.content == "message" &&
                value["role"]?.jsonPrimitive?.content == "assistant" && value["content"] is JsonArray &&
                value["stop_reason"] is JsonPrimitive && value["stop_reason"] != JsonNull
        }
    }.getOrDefault(false)
}
