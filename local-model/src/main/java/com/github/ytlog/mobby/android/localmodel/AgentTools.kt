package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.*

internal data class ToolContext(
    val messages: JsonArray,
    val tools: JsonArray,
    val choice: String,
    val parallel: Boolean,
    val customTools: Set<String>,
    val enableThinking: Boolean,
    val temperature: Float,
    val topP: Float,
)

/** Converts native protocol tool turns to the llama.cpp chat-template representation. */
internal object AgentTools {
    private fun string(obj: JsonObject, key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw BadRequest("$key must be a string")

    private fun text(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: throw BadRequest("Text must be a string")
        is JsonArray -> value.joinToString("\n") { part ->
            val item = part as? JsonObject ?: throw BadRequest("Unsupported content block")
            if (item["type"]?.jsonPrimitive?.content !in listOf("text", "input_text", "output_text")) throw BadRequest("Unsupported content block")
            string(item, "text")
        }
        else -> throw BadRequest("Unsupported content")
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject { put("role", role); put("content", content) }
    private fun toolResult(id: String, content: String): JsonObject = buildJsonObject {
        put("role", "tool"); put("tool_call_id", id); put("content", content)
    }
    private fun call(id: String, name: String, args: String): JsonObject = buildJsonObject {
        put("id", id); put("type", "function")
        putJsonObject("function") { put("name", name); put("arguments", args) }
    }
    private fun assistantCall(id: String, name: String, args: String): JsonObject = buildJsonObject {
        put("role", "assistant"); put("content", JsonNull); put("tool_calls", JsonArray(listOf(call(id, name, args))))
    }
    private fun appendAssistantCall(messages: MutableList<JsonObject>, id: String, name: String, args: String) {
        val previous = messages.lastOrNull()
        if (previous?.get("role")?.jsonPrimitive?.content == "assistant") {
            messages[messages.lastIndex] = buildJsonObject {
                previous.forEach { (key, value) -> put(key, value) }
                put("tool_calls", JsonArray((previous["tool_calls"] as? JsonArray).orEmpty() + call(id, name, args)))
            }
        } else messages += assistantCall(id, name, args)
    }

    fun parse(obj: JsonObject, protocol: String): ToolContext? {
        val rawTools = obj["tools"] as? JsonArray ?: if (obj["tools"] == null) JsonArray(emptyList()) else throw BadRequest("tools must be an array")
        val source = obj[if (protocol == "responses") "input" else "messages"]
        val historyHasTools = (source as? JsonArray)?.any { item ->
            val entry = item as? JsonObject ?: return@any false
            entry["type"]?.jsonPrimitive?.content in listOf("function_call", "function_call_output", "custom_tool_call", "custom_tool_call_output") ||
                entry["tool_calls"] != null || (entry["content"] as? JsonArray)?.any { block ->
                    (block as? JsonObject)?.get("type")?.jsonPrimitive?.content in listOf("tool_use", "tool_result")
                } == true
        } == true
        if (rawTools.isEmpty() && !historyHasTools) return null

        val custom = mutableSetOf<String>()
        val tools = rawTools.map { entry ->
            val tool = entry as? JsonObject ?: throw BadRequest("Tool must be an object")
            when (protocol) {
                "responses" -> {
                    val type = string(tool, "type")
                    val name = string(tool, "name")
                    if (tool["strict"]?.jsonPrimitive?.booleanOrNull == true) throw BadRequest("Strict tool schemas are not supported")
                    val schema = when (type) {
                        "function" -> tool["parameters"] ?: buildJsonObject { put("type", "object") }
                        "custom" -> {
                            custom += name
                            buildJsonObject {
                                put("type", "object")
                                putJsonObject("properties") { putJsonObject("input") { put("type", "string") } }
                                put("required", JsonArray(listOf(JsonPrimitive("input"))))
                            }
                        }
                        else -> throw BadRequest("Unsupported tool type: $type")
                    }
                    if (type == "custom" && tool["format"]?.jsonObject?.get("type")?.jsonPrimitive?.content !in listOf(null, "text")) throw BadRequest("Unsupported custom tool format")
                    functionTool(name, tool["description"]?.jsonPrimitive?.contentOrNull ?: "", schema)
                }
                "messages" -> functionTool(string(tool, "name"), tool["description"]?.jsonPrimitive?.contentOrNull ?: "", tool["input_schema"] ?: throw BadRequest("input_schema is required"))
                else -> {
                    if (string(tool, "type") != "function") throw BadRequest("Unsupported tool type")
                    val function = tool["function"] as? JsonObject ?: throw BadRequest("function is required")
                    if (function["strict"]?.jsonPrimitive?.booleanOrNull == true) throw BadRequest("Strict tool schemas are not supported")
                    functionTool(string(function, "name"), function["description"]?.jsonPrimitive?.contentOrNull ?: "", function["parameters"] ?: buildJsonObject { put("type", "object") })
                }
            }
        }
        val names = tools.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        if (names.size != names.distinct().size) throw BadRequest("Duplicate tool name")
        val choice = when (val value = obj["tool_choice"]) {
            null -> "auto"
            is JsonPrimitive -> value.content.takeIf { it in listOf("auto", "none", "required", "any") }?.let { if (it == "any") "required" else it }
                ?: throw BadRequest("Unsupported tool_choice")
            is JsonObject -> {
                val type = value["type"]?.jsonPrimitive?.content
                if (type in listOf("auto", "none", "any", "required")) {
                    if (value.keys != setOf("type")) throw BadRequest("Unsupported tool_choice")
                    if (type == "any") "required" else type!!
                } else {
                val selected = if (protocol == "chat") (value["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
                    else value["name"]?.jsonPrimitive?.content
                if (selected !in names) throw BadRequest("Unknown tool_choice name")
                "required:$selected"
                }
            }
            else -> throw BadRequest("Unsupported tool_choice")
        }
        val parallel = obj["parallel_tool_calls"]?.let { it.jsonPrimitive.booleanOrNull ?: throw BadRequest("parallel_tool_calls must be boolean") } ?: false
        val effort = (obj["reasoning"] as? JsonObject)?.get("effort")?.jsonPrimitive?.contentOrNull
        if (effort != null && effort !in listOf("none", "minimal", "low", "medium", "high", "xhigh")) throw BadRequest("Unsupported reasoning effort")
        val messages = when (protocol) {
            "responses" -> responsesMessages(obj)
            "messages" -> anthropicMessages(obj)
            else -> chatMessages(obj)
        }
        if (messages.isEmpty()) throw BadRequest("No messages")
        validateHistory(messages)
        val temperature = obj["temperature"]?.let { it.jsonPrimitive.doubleOrNull?.toFloat() ?: throw BadRequest("temperature must be a number") } ?: 0f
        val topP = obj["top_p"]?.let { it.jsonPrimitive.doubleOrNull?.toFloat() ?: throw BadRequest("top_p must be a number") } ?: 1f
        if (temperature !in 0f..2f || topP <= 0f || topP > 1f) throw BadRequest("Invalid sampling settings")
        if (effort != null && effort != "none") throw BadRequest("Explicit reasoning effort is not supported by this model profile")
        return ToolContext(JsonArray(messages), JsonArray(tools), choice, parallel, custom,
            false, temperature, topP)
    }

    private fun validateHistory(messages: List<JsonObject>) {
        val calls = mutableSetOf<String>()
        val results = mutableSetOf<String>()
        messages.forEach { message ->
            message["tool_calls"]?.jsonArray?.forEach { call ->
                val id = call.jsonObject["id"]!!.jsonPrimitive.content
                if (id.isBlank() || !calls.add(id)) throw BadRequest("Duplicate or empty tool call ID")
            }
            if (message["role"]?.jsonPrimitive?.content == "tool") {
                val id = message["tool_call_id"]!!.jsonPrimitive.content
                if (id !in calls || !results.add(id)) throw BadRequest("Tool result has no matching call")
            }
        }
    }

    private fun functionTool(name: String, description: String, schema: JsonElement): JsonObject {
        if (name.isBlank() || name.length > 128 || schema !is JsonObject) throw BadRequest("Invalid tool definition")
        return buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", name); put("description", description); put("parameters", schema) }
        }
    }

    private fun responsesMessages(obj: JsonObject): List<JsonObject> = buildList {
        obj["instructions"]?.let { add(message("system", text(it))) }
        val input = obj["input"] ?: throw BadRequest("input is required")
        if (input is JsonPrimitive) { add(message("user", text(input))); return@buildList }
        for (entry in input as? JsonArray ?: throw BadRequest("Unsupported input")) {
            val item = entry as? JsonObject ?: throw BadRequest("Input item must be an object")
            when (item["type"]?.jsonPrimitive?.content ?: "message") {
                "message" -> {
                    val role = string(item, "role")
                    if (role !in listOf("system", "developer", "user", "assistant")) throw BadRequest("Unsupported role: $role")
                    add(message(if (role == "developer") "system" else role, text(item["content"] ?: throw BadRequest("content is required"))))
                }
                "function_call", "custom_tool_call" -> {
                    val id = string(item, "call_id")
                    val name = string(item, "name")
                    val args = if (item["type"]?.jsonPrimitive?.content == "custom_tool_call") buildJsonObject { put("input", string(item, "input")) }.toString()
                        else string(item, "arguments")
                    appendAssistantCall(this, id, name, args)
                }
                "function_call_output", "custom_tool_call_output" -> add(toolResult(string(item, "call_id"), text(item["output"] ?: throw BadRequest("output is required"))))
                else -> throw BadRequest("Unsupported input item type")
            }
        }
    }

    private fun anthropicMessages(obj: JsonObject): List<JsonObject> = buildList {
        obj["system"]?.let { add(message("system", text(it))) }
        for (entry in obj["messages"] as? JsonArray ?: throw BadRequest("messages is required")) {
            val item = entry as? JsonObject ?: throw BadRequest("Message must be an object")
            val role = string(item, "role")
            if (role !in listOf("user", "assistant")) throw BadRequest("Unsupported role: $role")
            val content = item["content"] ?: throw BadRequest("content is required")
            if (content is JsonPrimitive) { add(message(role, text(content))); continue }
            for (block in content as? JsonArray ?: throw BadRequest("Unsupported content")) {
                val part = block as? JsonObject ?: throw BadRequest("Unsupported content block")
                when (string(part, "type")) {
                    "text" -> add(message(role, string(part, "text")))
                    "tool_use" -> appendAssistantCall(this, string(part, "id"), string(part, "name"), (part["input"] ?: throw BadRequest("input is required")).toString())
                    "tool_result" -> add(toolResult(string(part, "tool_use_id"), text(part["content"] ?: throw BadRequest("content is required"))))
                    else -> throw BadRequest("Unsupported content block")
                }
            }
        }
    }

    private fun chatMessages(obj: JsonObject): List<JsonObject> = buildList {
        for (entry in obj["messages"] as? JsonArray ?: throw BadRequest("messages is required")) {
            val item = entry as? JsonObject ?: throw BadRequest("Message must be an object")
            val role = string(item, "role")
            when (role) {
                "system", "user", "assistant" -> {
                    val calls = item["tool_calls"] as? JsonArray
                    if (calls != null) {
                        val normalized = calls.map { raw ->
                            val call = raw as? JsonObject ?: throw BadRequest("Invalid tool call")
                            val function = call["function"] as? JsonObject ?: throw BadRequest("function is required")
                            call(string(call, "id"), string(function, "name"), string(function, "arguments"))
                        }
                        add(buildJsonObject { put("role", "assistant"); put("content", item["content"]?.let { JsonPrimitive(text(it)) } ?: JsonNull); put("tool_calls", JsonArray(normalized)) })
                    } else add(message(role, text(item["content"] ?: throw BadRequest("content is required"))))
                }
                "tool" -> add(toolResult(string(item, "tool_call_id"), text(item["content"] ?: throw BadRequest("content is required"))))
                else -> throw BadRequest("Unsupported role: $role")
            }
        }
    }
}
