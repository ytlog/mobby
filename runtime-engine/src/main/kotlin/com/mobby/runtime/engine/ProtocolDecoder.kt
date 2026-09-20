package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import kotlinx.serialization.json.*

/** Only public CLI messages/tool output. Private reasoning blocks are deliberately not projected. */
sealed interface AgentFact {
    data class Session(val id: String) : AgentFact
    data class Text(val messageId: String, val text: String) : AgentFact
    data class Tool(val id: String, val kind: String, val summary: String, val output: String? = null, val outcome: ToolOutcome? = null) : AgentFact
    data class Diagnostic(val kind: String, val text: String) : AgentFact
    data class Completed(val success: Boolean, val error: ErrorCode? = null) : AgentFact
}

class ProtocolDecoder(private val agent: AgentId) {
    private var lastAssistant = ""
    private var fallbackId = 0
    fun decode(line: String): List<AgentFact> {
        if (agent == AgentId.CLAUDE_CODE && line.endsWith(" [line truncated]") &&
            Regex("""^\s*\{\s*"type"\s*:\s*"user"""").containsMatchIn(line))
            return listOf(AgentFact.Diagnostic("truncated-user-event", "CLI 用户事件超过输出行上限，无法完整解析"))
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return listOf(AgentFact.Diagnostic("invalid-json", line))
        return if (agent == AgentId.CODEX) codex(value, line) else claude(value, line)
    }
    private fun codex(value: JsonObject, line: String): List<AgentFact> { return when (value.text("type")) {
        "thread.started" -> value.text("thread_id")?.let { listOf(AgentFact.Session(it)) }.orEmpty()
        "turn.started" -> emptyList()
        "turn.completed" -> listOf(AgentFact.Completed(true))
        "turn.failed", "error" -> listOf(AgentFact.Diagnostic("error", value["error"]?.toString() ?: value.text("message") ?: "CLI error"), AgentFact.Completed(false, ErrorCode.PROTOCOL_ERROR))
        "item.started", "item.updated", "item.completed" -> {
            val item = value["item"] as? JsonObject ?: return listOf(AgentFact.Diagnostic("invalid-item", line))
            val type = item.text("type") ?: "unknown"
            val id = item.text("id") ?: "item-${fallbackId++}"
            val completed = value.text("type") == "item.completed"
            when (type) {
                "reasoning" -> emptyList()
                "agent_message" -> if (completed) listOf(AgentFact.Text(id, item.text("text").orEmpty())) else emptyList()
                "command_execution", "file_change", "mcp_tool_call", "web_search", "todo_list" -> {
                    val outcome = if (!completed) null else if (item.text("status") == "failed" ||
                        (item["exit_code"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } == true) ToolOutcome.FAILED else ToolOutcome.SUCCEEDED
                    val summary = item.text("command") ?: item.text("tool") ?: item.text("query") ?: type
                    val output = if (completed) item.text("aggregated_output") ?: item["changes"]?.toString()
                        ?: item["result"]?.toString() ?: item["error"]?.toString() ?: item["items"]?.toString() else null
                    listOf(AgentFact.Tool(id, type, summary, output, outcome))
                }
                else -> listOf(AgentFact.Diagnostic(type, line))
            }
        }
        else -> listOf(AgentFact.Diagnostic(value.text("type") ?: "unknown", line))
    }
    }

    private fun claude(value: JsonObject, line: String): List<AgentFact> = buildList {
        value.text("session_id")?.let { add(AgentFact.Session(it)) }
        when (value.text("type")) {
            "system" -> if (value.text("subtype") != "init") add(AgentFact.Diagnostic(value.text("subtype") ?: "system", line))
            "assistant", "user" -> {
                val message = value["message"] as? JsonObject
                val id = message?.text("id") ?: "message-${fallbackId++}"
                val blocks = message?.get("content") as? JsonArray ?: return@buildList
                val text = blocks.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.text("type") == "text" }?.text("text") }.joinToString("\n")
                if (text.isNotEmpty() && value.text("type") == "assistant") { add(AgentFact.Text(id, text)); lastAssistant = text }
                for (block in blocks.filterIsInstance<JsonObject>()) when (block.text("type")) {
                    "tool_use" -> add(AgentFact.Tool(block.text("id") ?: "tool-${fallbackId++}", block.text("name") ?: "tool", block["input"]?.toString().orEmpty()))
                    "tool_result" -> add(AgentFact.Tool(block.text("tool_use_id") ?: "tool-${fallbackId++}", "tool", "",
                        block["content"]?.let { content -> if (content is JsonPrimitive) content.content else content.toString() },
                        if ((block["is_error"] as? JsonPrimitive)?.booleanOrNull == true) ToolOutcome.FAILED else ToolOutcome.SUCCEEDED))
                    "image" -> add(AgentFact.Diagnostic("image", "图片输入（内容不写入诊断日志）"))
                    "thinking", "redacted_thinking", "text" -> Unit
                    else -> add(AgentFact.Diagnostic(block.text("type") ?: "unknown-content", block.toString()))
                }
            }
            "result" -> {
                val result = value.text("result").orEmpty()
                if (result.isNotEmpty() && result != lastAssistant) add(AgentFact.Text("result", result))
                val denied = (value["permission_denials"] as? JsonArray)?.isNotEmpty() == true
                val error = (value["is_error"] as? JsonPrimitive)?.booleanOrNull
                val success = error == false && value.text("subtype") == "success" && !denied
                if (!success) add(AgentFact.Diagnostic("result-error", value["errors"]?.toString() ?: value.text("subtype") ?: "Missing terminal evidence"))
                add(AgentFact.Completed(success, if (denied) ErrorCode.PERMISSION_DENIED else if (!success) ErrorCode.PROTOCOL_ERROR else null))
            }
            else -> add(AgentFact.Diagnostic(value.text("type") ?: "unknown", line))
        }
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

object AgentCommand {
    fun arguments(request: RunRequest, executable: String, prompt: String, imagePaths: List<String> = emptyList(), streamInput: Boolean = false): List<String> {
        require(imagePaths.all { it.startsWith("/") && '\u0000' !in it })
        require(request.agentId == AgentId.CODEX || imagePaths.isEmpty())
        require(request.agentId == AgentId.CLAUDE_CODE || !streamInput)
        val session = request.sessionRef?.value
        require(session == null || session.matches(Regex("[A-Za-z0-9-]{1,100}")))
        return when (request.agentId) {
            AgentId.CODEX -> buildList {
                add(executable); add("exec"); add("--json")
                request.reasoningLevel?.let { addAll(listOf("-c", "model_reasoning_effort=${JsonPrimitive(it)}")) }
                if (session != null) addAll(listOf("resume", session))
                imagePaths.forEach { addAll(listOf("--image", it)) }
                add("--"); add(prompt)
            }
            AgentId.CLAUDE_CODE -> buildList {
                addAll(listOf(executable, "-p", "--output-format", "stream-json", "--verbose"))
                request.reasoningLevel?.let { addAll(listOf("--effort", it)) }
                if (session != null) addAll(listOf("--resume", session))
                if (streamInput) addAll(listOf("--input-format", "stream-json"))
                else { add("--"); add(prompt) }
            }
        }
    }
}
