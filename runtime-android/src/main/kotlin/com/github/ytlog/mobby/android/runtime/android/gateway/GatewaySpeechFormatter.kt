package com.github.ytlog.mobby.android.runtime.android.gateway

import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** One bounded request using the saved gateway and its native protocol. No speech text is stored here. */
internal class GatewaySpeechFormatter(
    private val open: (java.net.URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun format(config: GatewayConfig, raw: String): String? = runInterruptible(Dispatchers.IO) {
        if (raw.isBlank() || raw.length > 8_000) return@runInterruptible null
        val instruction = "整理以下语音转写的标点、分句和明显的同音错误。保持原意，不回答内容，不添加事实。只输出整理后的文字。"
        val prompt = "$instruction\n\n$raw"
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", false)
            when (config.protocol) {
                GatewayProtocol.RESPONSES -> {
                    put("input", prompt)
                    put("max_output_tokens", 1024)
                    put("store", false)
                }
                GatewayProtocol.MESSAGES -> {
                    putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", prompt) } }
                    put("max_tokens", 1024)
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val path = if (config.protocol == GatewayProtocol.RESPONSES) "/responses" else "/messages"
        val connection = open(GatewayEndpoint.url(config.endpoint, path))
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (config.key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer ${config.key}")
            if (config.protocol == GatewayProtocol.MESSAGES) {
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                if (config.key.isNotEmpty()) connection.setRequestProperty("x-api-key", config.key)
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) return@runInterruptible null
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (output.size() <= 32_768) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.size > 32_768) return@runInterruptible null
            parseFormattedSpeech(config.protocol, bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

internal fun parseFormattedSpeech(protocol: GatewayProtocol, body: String): String? {
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    if (obj["error"] != null && obj["error"] != JsonNull) return null
    val text = when (protocol) {
        GatewayProtocol.RESPONSES -> {
            (obj["output_text"] as? JsonPrimitive)?.contentOrNull ?: (obj["output"] as? JsonArray)
                ?.flatMap { (it as? JsonObject)?.get("content") as? JsonArray ?: JsonArray(emptyList()) }
                ?.mapNotNull { item -> (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
        }
        GatewayProtocol.MESSAGES -> (obj["content"] as? JsonArray)
            ?.mapNotNull { item -> (item as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")
    }
    return text?.trim()?.takeIf { it.isNotBlank() && it.length <= 8_000 }
}
