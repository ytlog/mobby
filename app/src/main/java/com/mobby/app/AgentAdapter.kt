package com.mobby.app

import kotlinx.serialization.json.*

enum class AgentMode(val label: String) { SHELL("Shell"), CLAUDE("Claude Code"), CODEX("Codex") }
data class AgentOutput(val text: String, val error: Boolean = false)

object AgentAdapter {
    fun arguments(mode: AgentMode, executable: String, input: String): List<String> = when (mode) {
        AgentMode.SHELL -> listOf(executable, "-c", input)
        AgentMode.CLAUDE -> listOf(executable, "-p", "--output-format", "stream-json", "--verbose", "--", input)
        AgentMode.CODEX -> listOf(executable, "exec", "--json", "--", input)
    }
    fun parse(mode: AgentMode, line: String): AgentOutput {
        if (mode == AgentMode.SHELL) return AgentOutput(line)
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return AgentOutput(line)
        fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        return when (event.string("type")) {
            "assistant" -> {
                val content = (event["message"] as? JsonObject)?.get("content") as? JsonArray
                AgentOutput(content?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("\n")?.ifEmpty { line } ?: line)
            }
            "result" -> AgentOutput(event.string("result") ?: line, (event["is_error"] as? JsonPrimitive)?.booleanOrNull == true)
            "item.completed" -> {
                val item = event["item"] as? JsonObject
                AgentOutput(item?.string("text") ?: item?.string("aggregated_output") ?: line)
            }
            "error", "turn.failed" -> AgentOutput(event.string("message") ?: event["error"]?.toString() ?: line, true)
            else -> AgentOutput(line)
        }
    }
}
