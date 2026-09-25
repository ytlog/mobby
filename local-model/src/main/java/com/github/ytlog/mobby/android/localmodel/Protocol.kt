package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.*

internal data class InferenceRequest(val model: String, val messages: List<PromptMessage>, val maxTokens: Int, val stream: Boolean, val toolContext: ToolContext? = null)
internal class BadRequest(message: String) : IllegalArgumentException(message)
internal object Protocol {
    val json = Json { ignoreUnknownKeys = false }

    fun parse(body: String, protocol: String): InferenceRequest {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw BadRequest("Invalid JSON object") }
        val allowed = when (protocol) {
            "responses" -> setOf("model", "input", "instructions", "stream", "max_output_tokens", "store", "tools", "tool_choice", "parallel_tool_calls", "reasoning", "include", "client_metadata", "prompt_cache_key", "temperature", "top_p")
            "messages" -> setOf("model", "messages", "system", "stream", "max_tokens", "tools", "tool_choice", "metadata", "temperature", "top_p")
            "chat" -> setOf("model", "messages", "stream", "max_tokens", "tools", "tool_choice", "parallel_tool_calls", "temperature", "top_p")
            else -> emptySet()
        }
        (obj.keys - allowed).firstOrNull()?.let { throw BadRequest("Unsupported field: $it") }
        val model = obj["model"]?.let { plainString(it) } ?: throw BadRequest("model is required")
        val stream = obj["stream"]?.let { it.jsonPrimitive.booleanOrNull ?: throw BadRequest("stream must be boolean") } ?: false
        val max = obj[if (protocol == "responses") "max_output_tokens" else "max_tokens"]?.let {
            it.jsonPrimitive.intOrNull ?: throw BadRequest("max tokens must be integer")
        } ?: if (obj["tools"] != null) 512 else 128
        if (max !in 1..1024) throw BadRequest("max tokens out of range")
        if (protocol == "responses") {
            obj["store"]?.let { if (it.jsonPrimitive.booleanOrNull != false) throw BadRequest("store must be false") }
            obj["include"]?.let { value ->
                val items = value as? JsonArray ?: throw BadRequest("include must be an array")
                if (items.any { it.jsonPrimitive.content != "reasoning.encrypted_content" }) throw BadRequest("Unsupported include value")
            }
            obj["reasoning"]?.let { value ->
                val options = value as? JsonObject ?: throw BadRequest("reasoning must be an object")
                if ((options.keys - setOf("effort", "summary")).isNotEmpty()) throw BadRequest("Unsupported reasoning option")
                options["summary"]?.let { if (it.jsonPrimitive.content !in listOf("auto", "concise", "detailed")) throw BadRequest("Unsupported reasoning summary") }
            }
        }
        val toolContext = AgentTools.parse(obj, protocol)
        if (toolContext != null) return InferenceRequest(model, emptyList(), max, stream, toolContext)
        if (obj["reasoning"] != null) throw BadRequest("Reasoning requires a tool-capable model request")
        if (obj["temperature"] != null || obj["top_p"] != null) throw BadRequest("Sampling settings require a tool-capable model request")
        if (obj["tool_choice"]?.jsonPrimitive?.contentOrNull !in listOf(null, "auto", "none")) throw BadRequest("Tool settings require tools")
        if (obj["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull == true) throw BadRequest("Tool settings require tools")
        val messages = mutableListOf<PromptMessage>()
        when (protocol) {
            "responses" -> {
                obj["instructions"]?.let { messages += PromptMessage("system", plain(it)) }
                val input = obj["input"] ?: throw BadRequest("input is required")
                if (input is JsonPrimitive) messages += PromptMessage("user", plain(input))
                else if (input is JsonArray) input.forEach { messages += message(it.jsonObject, "responses") }
                else throw BadRequest("Unsupported input")
            }
            "messages", "chat" -> {
                if (protocol == "messages") obj["system"]?.let { messages += PromptMessage("system", plain(it)) }
                val input = obj["messages"] as? JsonArray ?: throw BadRequest("messages is required")
                input.forEach { messages += message(it.jsonObject, protocol) }
            }
        }
        if (messages.isEmpty()) throw BadRequest("No text messages")
        return InferenceRequest(model, messages, max, stream)
    }

    private fun plainString(value: JsonElement): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw BadRequest("Text must be a string")

    private fun plain(value: JsonElement): String = when (value) {
        is JsonPrimitive -> plainString(value)
        is JsonArray -> value.joinToString("") {
            val block = it as? JsonObject ?: throw BadRequest("Unsupported content block")
            if (block.keys != setOf("type", "text") || block["type"]?.jsonPrimitive?.content !in listOf("text", "input_text")) throw BadRequest("Unsupported content block")
            block["text"]?.let { plainString(it) } ?: throw BadRequest("text is required")
        }
        else -> throw BadRequest("Unsupported content")
    }

    private fun message(obj: JsonObject, protocol: String): PromptMessage {
        val allowed = if (protocol == "responses") setOf("role", "content", "type") else setOf("role", "content")
        (obj.keys - allowed).firstOrNull()?.let { throw BadRequest("Unsupported message field: $it") }
        if (protocol == "responses" && obj["type"]?.jsonPrimitive?.contentOrNull !in listOf(null, "message")) throw BadRequest("Unsupported item type")
        val role = obj["role"]?.let { plainString(it) } ?: throw BadRequest("role is required")
        if (role !in listOf("system", "user", "assistant")) throw BadRequest("Unsupported role: $role")
        return PromptMessage(role, plain(obj["content"] ?: throw BadRequest("content is required")))
    }

    fun error(message: String, type: String = "invalid_request_error"): String = buildJsonObject {
        putJsonObject("error") { put("type", type); put("message", message.take(160)) }
    }.toString()
}
