package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.*
import java.util.UUID

internal data class GeneratedCall(val id: String, val name: String, val arguments: String, val customInput: String?)
internal data class GeneratedTurn(val text: String, val calls: List<GeneratedCall>, val inputTokens: Int, val outputTokens: Int, val length: Boolean)

internal object ToolResponses {
    fun parse(raw: String, context: ToolContext): GeneratedTurn {
        val data = Protocol.json.parseToJsonElement(raw).jsonObject
        val names = context.tools.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val calls = (data["tool_calls"] as? JsonArray ?: throw IllegalStateException("Missing tool calls")).map { value ->
            val call = value.jsonObject
            val name = call["name"]?.jsonPrimitive?.content ?: throw IllegalStateException("Tool name is missing")
            if (name !in names) throw IllegalStateException("Model requested an unknown tool: $name")
            val arguments = call["arguments"]?.jsonPrimitive?.content ?: throw IllegalStateException("Tool arguments are missing")
            val parsed = runCatching { Protocol.json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { throw IllegalStateException("Model produced invalid JSON tool arguments") }
            val customInput = if (name in context.customTools) parsed["input"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Model produced invalid custom tool input") else null
            GeneratedCall(call["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "call_${UUID.randomUUID().toString().replace("-", "")}",
                name, arguments, customInput)
        }
        if (context.choice.startsWith("required") && calls.isEmpty()) throw IllegalStateException("Model did not call the required tool")
        if (context.choice == "none" && calls.isNotEmpty()) throw IllegalStateException("Model called a disabled tool")
        if (!context.parallel && calls.size > 1) throw IllegalStateException("Model generated parallel calls when disabled")
        if (context.choice.startsWith("required:")) {
            val required = context.choice.substringAfter(':')
            if (calls.any { it.name != required }) throw IllegalStateException("Model called a different tool")
        }
        val text = data["content"]?.jsonPrimitive?.content ?: ""
        val inputTokens = data["input_tokens"]?.jsonPrimitive?.intOrNull ?: throw IllegalStateException("Missing input token count")
        val outputTokens = data["output_tokens"]?.jsonPrimitive?.intOrNull ?: throw IllegalStateException("Missing output token count")
        val length = data["length"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalStateException("Missing generation stop status")
        if (length && calls.isNotEmpty()) throw IllegalStateException("Tool call was cut off by token limit")
        if (text.isBlank() && calls.isEmpty() && !length) throw IllegalStateException("Model returned neither text nor a tool call")
        return GeneratedTurn(text, calls, inputTokens, outputTokens, length)
    }

    fun completion(protocol: String, id: String, model: String, created: Long, turn: GeneratedTurn): JsonObject = when (protocol) {
        "responses" -> response(id, model, created, turn)
        "messages" -> anthropic(id, model, turn)
        else -> chat(id, model, created, turn)
    }

    private fun response(id: String, model: String, created: Long, turn: GeneratedTurn): JsonObject = buildJsonObject {
        put("id", id); put("object", "response"); put("created_at", created); put("model", model)
        put("status", if (turn.length) "incomplete" else "completed")
        put("error", JsonNull)
        put("incomplete_details", if (turn.length) buildJsonObject { put("reason", "max_output_tokens") } else JsonNull)
        put("output", JsonArray(buildList {
            if (turn.text.isNotEmpty()) add(buildJsonObject {
                put("id", "msg_$id"); put("type", "message"); put("role", "assistant"); put("status", "completed")
                put("content", JsonArray(listOf(buildJsonObject { put("type", "output_text"); put("text", turn.text); put("annotations", JsonArray(emptyList())) })))
            })
            turn.calls.forEachIndexed { index, call -> add(buildJsonObject {
                put("id", "${if (call.customInput == null) "fc" else "ct"}_${id}_$index")
                put("type", if (call.customInput == null) "function_call" else "custom_tool_call")
                put("status", "completed"); put("call_id", call.id); put("name", call.name)
                if (call.customInput == null) put("arguments", call.arguments) else put("input", call.customInput)
            }) }
        }))
        put("usage", usage(turn, true))
    }

    private fun anthropic(id: String, model: String, turn: GeneratedTurn): JsonObject = buildJsonObject {
        put("id", id); put("type", "message"); put("role", "assistant"); put("model", model)
        put("content", JsonArray(buildList {
            if (turn.text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", turn.text) })
            turn.calls.forEach { call -> add(buildJsonObject {
                put("type", "tool_use"); put("id", call.id); put("name", call.name)
                put("input", Protocol.json.parseToJsonElement(call.arguments))
            }) }
        }))
        put("stop_reason", if (turn.length) "max_tokens" else if (turn.calls.isNotEmpty()) "tool_use" else "end_turn")
        put("stop_sequence", JsonNull); put("usage", usage(turn, false))
    }

    private fun chat(id: String, model: String, created: Long, turn: GeneratedTurn): JsonObject = buildJsonObject {
        put("id", id); put("object", "chat.completion"); put("created", created); put("model", model)
        put("choices", JsonArray(listOf(buildJsonObject {
            put("index", 0)
            put("message", buildJsonObject {
                put("role", "assistant"); put("content", if (turn.text.isEmpty()) JsonNull else JsonPrimitive(turn.text))
                if (turn.calls.isNotEmpty()) put("tool_calls", JsonArray(turn.calls.map { call -> buildJsonObject {
                    put("id", call.id); put("type", "function")
                    put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                } }))
            })
            put("finish_reason", if (turn.length) "length" else if (turn.calls.isNotEmpty()) "tool_calls" else "stop")
        })))
        put("usage", usage(turn, false))
    }

    private fun usage(turn: GeneratedTurn, responses: Boolean): JsonObject = buildJsonObject {
        put("input_tokens", turn.inputTokens); put("output_tokens", turn.outputTokens)
        if (responses) put("total_tokens", turn.inputTokens + turn.outputTokens)
    }

    fun events(protocol: String, completed: JsonObject): List<Pair<String?, JsonObject>> = when (protocol) {
        "responses" -> responsesEvents(completed)
        "messages" -> messagesEvents(completed)
        else -> chatEvents(completed)
    }

    private fun responsesEvents(result: JsonObject): List<Pair<String?, JsonObject>> = buildList {
        var sequence = 0
        fun emit(type: String, fields: JsonObject) { add(type to buildJsonObject { put("type", type); put("sequence_number", sequence++); fields.forEach { (k, v) -> put(k, v) } }) }
        val start = buildJsonObject { result.forEach { (k, v) -> put(k, v) }; put("status", "in_progress"); put("output", JsonArray(emptyList())); put("usage", JsonNull) }
        emit("response.created", buildJsonObject { put("response", start) })
        emit("response.in_progress", buildJsonObject { put("response", start) })
        result["output"]!!.jsonArray.forEachIndexed { index, itemValue ->
            val item = itemValue.jsonObject
            val type = item["type"]!!.jsonPrimitive.content
            val pending = buildJsonObject { item.forEach { (k, v) -> put(k, v) }; put("status", "in_progress"); when (type) {
                "message" -> put("content", JsonArray(emptyList()))
                "function_call" -> put("arguments", "")
                else -> put("input", "")
            } }
            emit("response.output_item.added", buildJsonObject { put("output_index", index); put("item", pending) })
            if (type == "message") {
                val part = item["content"]!!.jsonArray[0].jsonObject
                val empty = buildJsonObject { part.forEach { (k, v) -> put(k, v) }; put("text", "") }
                emit("response.content_part.added", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put("content_index", 0); put("part", empty) })
                emit("response.output_text.delta", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put("content_index", 0); put("delta", part["text"]!!) })
                emit("response.output_text.done", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put("content_index", 0); put("text", part["text"]!!) })
                emit("response.content_part.done", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put("content_index", 0); put("part", part) })
            } else {
                val field = if (type == "function_call") "arguments" else "input"
                val stem = if (type == "function_call") "response.function_call_arguments" else "response.custom_tool_call_input"
                emit("$stem.delta", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put("delta", item[field]!!) })
                emit("$stem.done", buildJsonObject { put("item_id", item["id"]!!); put("output_index", index); put(field, item[field]!!) })
            }
            emit("response.output_item.done", buildJsonObject { put("output_index", index); put("item", item) })
        }
        val end = if (result["status"]?.jsonPrimitive?.content == "incomplete") "response.incomplete" else "response.completed"
        emit(end, buildJsonObject { put("response", result) })
    }

    private fun messagesEvents(result: JsonObject): List<Pair<String?, JsonObject>> = buildList {
        fun emit(type: String, fields: JsonObject = buildJsonObject {} ) { add(type to buildJsonObject { put("type", type); fields.forEach { (k, v) -> put(k, v) } }) }
        val initial = buildJsonObject { result.forEach { (k, v) -> put(k, v) }; put("content", JsonArray(emptyList())); put("stop_reason", JsonNull)
            put("usage", buildJsonObject { put("input_tokens", result["usage"]!!.jsonObject["input_tokens"]!!); put("output_tokens", 0) }) }
        emit("message_start", buildJsonObject { put("message", initial) })
        result["content"]!!.jsonArray.forEachIndexed { index, value ->
            val block = value.jsonObject
            val isTool = block["type"]!!.jsonPrimitive.content == "tool_use"
            val initialBlock = if (isTool) buildJsonObject { block.forEach { (k, v) -> put(k, v) }; put("input", buildJsonObject {}) }
                else buildJsonObject { put("type", "text"); put("text", "") }
            emit("content_block_start", buildJsonObject { put("index", index); put("content_block", initialBlock) })
            emit("content_block_delta", buildJsonObject { put("index", index); put("delta", if (isTool)
                buildJsonObject { put("type", "input_json_delta"); put("partial_json", block["input"]!!.toString()) }
                else buildJsonObject { put("type", "text_delta"); put("text", block["text"]!!) }) })
            emit("content_block_stop", buildJsonObject { put("index", index) })
        }
        emit("message_delta", buildJsonObject { put("delta", buildJsonObject { put("stop_reason", result["stop_reason"]!!); put("stop_sequence", JsonNull) });
            put("usage", buildJsonObject { put("output_tokens", result["usage"]!!.jsonObject["output_tokens"]!!) }) })
        emit("message_stop")
    }

    private fun chatEvents(result: JsonObject): List<Pair<String?, JsonObject>> = buildList {
        val choice = result["choices"]!!.jsonArray[0].jsonObject
        val content = choice["message"]!!.jsonObject
        fun chunk(delta: JsonObject, finish: JsonElement = JsonNull) = buildJsonObject {
            put("id", result["id"]!!); put("object", "chat.completion.chunk"); put("created", result["created"]!!); put("model", result["model"]!!)
            put("choices", JsonArray(listOf(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", finish) })))
        }
        add(null to chunk(buildJsonObject { put("role", "assistant") }))
        content["content"]?.takeUnless { it == JsonNull }?.let { add(null to chunk(buildJsonObject { put("content", it) })) }
        content["tool_calls"]?.jsonArray?.forEachIndexed { index, call ->
            add(null to chunk(buildJsonObject { put("tool_calls", JsonArray(listOf(buildJsonObject {
                put("index", index); call.jsonObject.forEach { (k, v) -> put(k, v) }
            }))) }))
        }
        add(null to chunk(buildJsonObject {}, choice["finish_reason"]!!))
    }
}
