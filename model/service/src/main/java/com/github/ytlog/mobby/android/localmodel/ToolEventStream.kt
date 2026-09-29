package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.*

/** Emits native SSE text while llama.cpp is generating; tool calls follow the final validated parse. */
internal class ToolEventStream(private val protocol: String, private val id: String, private val model: String, private val created: Long) {
    private val text = StringBuilder()
    private var inputTokens = 0
    private var nextSequence = 0
    private var textStarted = false

    private fun numbered(events: List<Pair<String?, JsonObject>>): List<Pair<String?, JsonObject>> = events.map { (name, body) ->
        if (protocol != "responses") name to body else name to buildJsonObject {
            body.forEach { (key, value) -> if (key != "sequence_number") put(key, value) }
            put("sequence_number", nextSequence++)
        }
    }

    private fun fixture(content: String): List<Pair<String?, JsonObject>> = ToolResponses.events(protocol,
        ToolResponses.completion(protocol, id, model, created, GeneratedTurn(content, emptyList(), inputTokens, 0, false)))

    fun start(tokens: Int): List<Pair<String?, JsonObject>> {
        inputTokens = tokens
        return numbered(fixture("").take(if (protocol == "responses") 2 else 1))
    }

    fun delta(piece: String): List<Pair<String?, JsonObject>> {
        if (piece.isEmpty()) return emptyList()
        text.append(piece)
        val result = mutableListOf<Pair<String?, JsonObject>>()
        if (!textStarted) {
            val initial = fixture("x")
            when (protocol) {
                "responses" -> result += initial.slice(2..3)
                "messages" -> result += initial[1]
            }
            textStarted = true
        }
        val quoted = JsonPrimitive(piece)
        result += when (protocol) {
            "responses" -> "response.output_text.delta" to buildJsonObject {
                put("type", "response.output_text.delta"); put("item_id", "msg_$id")
                put("output_index", 0); put("content_index", 0); put("delta", quoted)
            }
            "messages" -> "content_block_delta" to buildJsonObject {
                put("type", "content_block_delta"); put("index", 0)
                putJsonObject("delta") { put("type", "text_delta"); put("text", quoted) }
            }
            else -> null to buildJsonObject {
                put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
                putJsonArray("choices") { add(buildJsonObject {
                    put("index", 0); put("finish_reason", JsonNull)
                    putJsonObject("delta") { put("content", quoted) }
                }) }
            }
        }
        return numbered(result)
    }

    fun finish(completed: JsonObject): List<Pair<String?, JsonObject>> {
        val finalText = when (protocol) {
            "responses" -> completed["output"]!!.jsonArray.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "message" }
                ?.jsonObject?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            "messages" -> completed["content"]!!.jsonArray.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            else -> completed["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
        }
        check(finalText.startsWith(text.toString())) { "Streaming model text changed during final parse" }
        val remaining = delta(finalText.substring(text.length))
        val all = ToolResponses.events(protocol, completed)
        val ending = when (protocol) {
            "responses" -> all.drop(if (textStarted) 5 else 2)
            "messages" -> all.drop(if (textStarted) 3 else 1)
            else -> all.drop(if (textStarted) 2 else 1)
        }
        return remaining + numbered(ending)
    }
}
