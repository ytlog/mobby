package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import kotlinx.serialization.json.*

/** Only public CLI messages/tool output. Private reasoning blocks are deliberately not projected. */
sealed interface AgentFact {
    data class Session(val id: String) : AgentFact
    data class Text(val messageId: String, val text: String) : AgentFact
    data class Proposal(val markdown: String) : AgentFact
    data class Tool(val id: String, val kind: String, val summary: String, val output: String? = null, val outcome: ToolOutcome? = null) : AgentFact
    data class Diagnostic(val kind: String, val text: String) : AgentFact
    data class Completed(val success: Boolean, val error: ErrorCode? = null) : AgentFact
    data class Approval(val id: String, val action: String, val scope: String) : AgentFact
    object InvalidApproval : AgentFact
}

class ProtocolDecoder(private val agent: AgentId, private val requestedOutput: RequestedOutput = RequestedOutput.TEXT) {
    private var skillResult: SkillGeneration.Result? = null
    private val structuredTools = mutableSetOf<String>()
    private var lastAssistant = ""
    private var fallbackId = 0
    fun decode(line: String): List<AgentFact> {
        val controlRequest = agent == AgentId.CLAUDE_CODE && Regex("""^\s*\{\s*"type"\s*:\s*"control_request"""").containsMatchIn(line)
        if (controlRequest && line.endsWith(" [line truncated]"))
            return listOf(AgentFact.Diagnostic("truncated-approval", "CLI 审批请求超过输出行上限，已停止授权流程"), AgentFact.InvalidApproval)
        if (agent == AgentId.CLAUDE_CODE && line.endsWith(" [line truncated]") &&
            Regex("""^\s*\{\s*"type"\s*:\s*"user"""").containsMatchIn(line))
            return listOf(AgentFact.Diagnostic("truncated-user-event", "CLI 用户事件超过输出行上限，无法完整解析"))
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return if (controlRequest) listOf(AgentFact.Diagnostic("invalid-approval", "无法解析 CLI 审批请求，已停止授权流程"), AgentFact.InvalidApproval)
                else listOf(AgentFact.Diagnostic("invalid-json", line))
        return if (agent == AgentId.CODEX) codex(value, line) else claude(value, line)
    }
    private fun codex(value: JsonObject, line: String): List<AgentFact> { return when (value.text("type")) {
        "thread.started" -> value.text("thread_id")?.let { listOf(AgentFact.Session(it)) }.orEmpty()
        "turn.started" -> emptyList()
        "turn.completed" -> if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) finishSkill(skillResult) else listOf(AgentFact.Completed(true))
        "turn.failed", "error" -> listOf(AgentFact.Diagnostic("error", value["error"]?.toString() ?: value.text("message") ?: "CLI error"), AgentFact.Completed(false, ErrorCode.PROTOCOL_ERROR))
        "item.started", "item.updated", "item.completed" -> {
            val item = value["item"] as? JsonObject ?: return listOf(AgentFact.Diagnostic("invalid-item", line))
            val type = item.text("type") ?: "unknown"
            val id = item.text("id") ?: "item-${fallbackId++}"
            val completed = value.text("type") == "item.completed"
            when (type) {
                "reasoning" -> emptyList()
                "agent_message" -> if (completed) {
                    val text = item.text("text").orEmpty()
                    if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                        skillResult = SkillGeneration.parse(text)
                        listOf(AgentFact.Text(id, skillResult?.message ?: text))
                    } else listOf(AgentFact.Text(id, text))
                } else emptyList()
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
            "control_request" -> {
                val request = value["request"] as? JsonObject
                val id = (value["request_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val tool = (request?.get("tool_name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val input = request?.get("input") as? JsonObject
                if (request?.text("subtype") == "can_use_tool" && !id.isNullOrBlank() && id.length <= 256 &&
                    !tool.isNullOrBlank() && tool.length <= 256 && input != null && input.toString().toByteArray().size <= 65536) {
                    add(AgentFact.Approval(id, tool, input.toString()))
                } else {
                    add(AgentFact.Diagnostic("invalid-approval", "无法完整解析 CLI 审批请求，已停止授权流程"))
                    add(AgentFact.InvalidApproval)
                }
            }
            "system" -> if (value.text("subtype") != "init") add(AgentFact.Diagnostic(value.text("subtype") ?: "system", line))
            "assistant", "user" -> {
                val message = value["message"] as? JsonObject
                val id = message?.text("id") ?: "message-${fallbackId++}"
                val blocks = message?.get("content") as? JsonArray ?: return@buildList
                val text = blocks.mapNotNull { (it as? JsonObject)?.takeIf { block -> block.text("type") == "text" }?.text("text") }.joinToString("\n")
                if (text.isNotEmpty() && value.text("type") == "assistant") { add(AgentFact.Text(id, text)); lastAssistant = text }
                for (block in blocks.filterIsInstance<JsonObject>()) {
                    if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                        if (block.text("type") == "tool_use" && block.text("name") == "StructuredOutput") {
                            block.text("id")?.let { structuredTools.add(it) }; continue
                        }
                        if (block.text("type") == "tool_result" && block.text("tool_use_id") in structuredTools) continue
                    }
                    when (block.text("type")) {
                    "tool_use" -> add(AgentFact.Tool(block.text("id") ?: "tool-${fallbackId++}", block.text("name") ?: "tool", block["input"]?.toString().orEmpty()))
                    "tool_result" -> add(AgentFact.Tool(block.text("tool_use_id") ?: "tool-${fallbackId++}", "tool", "",
                        block["content"]?.let { content -> if (content is JsonPrimitive) content.content else content.toString() },
                        if ((block["is_error"] as? JsonPrimitive)?.booleanOrNull == true) ToolOutcome.FAILED else ToolOutcome.SUCCEEDED))
                    "image" -> add(AgentFact.Diagnostic("image", "图片输入（内容不写入诊断日志）"))
                    "thinking", "redacted_thinking", "text" -> Unit
                    else -> add(AgentFact.Diagnostic(block.text("type") ?: "unknown-content", block.toString()))
                    }
                }
            }
            "result" -> {
                val result = value.text("result").orEmpty()
                if (requestedOutput == RequestedOutput.TEXT && result.isNotEmpty() && result != lastAssistant) add(AgentFact.Text("result", result))
                val denied = (value["permission_denials"] as? JsonArray)?.isNotEmpty() == true
                val error = (value["is_error"] as? JsonPrimitive)?.booleanOrNull
                val success = error == false && value.text("subtype") == "success" && !denied
                if (!success) add(AgentFact.Diagnostic("result-error", value["errors"]?.toString() ?: value.text("subtype") ?: "Missing terminal evidence"))
                if (success && requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                    val structured = value["structured_output"]?.toString()?.let(SkillGeneration::parse)
                    structured?.let { if (it.message != lastAssistant) add(AgentFact.Text("result", it.message)) }
                    addAll(finishSkill(structured))
                } else add(AgentFact.Completed(success, if (denied) ErrorCode.PERMISSION_DENIED else if (!success) ErrorCode.PROTOCOL_ERROR else null))
            }
            else -> add(AgentFact.Diagnostic(value.text("type") ?: "unknown", line))
        }
    }
    private fun finishSkill(result: SkillGeneration.Result?): List<AgentFact> = if (result == null)
        listOf(AgentFact.Diagnostic("invalid-skill-output", "技能生成结果不符合结构要求，请重试或补充需求"), AgentFact.Completed(false, ErrorCode.PROTOCOL_ERROR))
    else buildList {
        result.markdown?.let { add(AgentFact.Proposal(it)) }
        add(AgentFact.Completed(true))
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

object AgentCommand {
    fun arguments(request: RunRequest, executable: String, prompt: String, imagePaths: List<String> = emptyList(), streamInput: Boolean = false, approvals: Boolean = false, schemaPath: String? = null): List<String> {
        require(imagePaths.all { it.startsWith("/") && '\u0000' !in it })
        require(request.agentId == AgentId.CODEX || imagePaths.isEmpty())
        require(request.agentId == AgentId.CLAUDE_CODE || !streamInput)
        require(!approvals || request.agentId == AgentId.CLAUDE_CODE && streamInput)
        val structured = request.requestedOutput == RequestedOutput.SKILL_PROPOSAL
        require(schemaPath == null || structured && request.agentId == AgentId.CODEX && schemaPath.startsWith("/") && '\u0000' !in schemaPath)
        require(!structured || request.agentId != AgentId.CODEX || schemaPath != null)
        val session = request.sessionRef?.value
        require(session == null || session.matches(Regex("[A-Za-z0-9-]{1,100}")))
        return when (request.agentId) {
            AgentId.CODEX -> buildList {
                // Android UID/SELinux is the user-authorized execution boundary. The phone
                // cannot provide Codex's additional Linux namespace sandbox.
                addAll(listOf(executable, "--sandbox", "danger-full-access",
                    "-c", "approval_policy=\"never\""))
                request.reasoningLevel?.let { addAll(listOf("-c", "model_reasoning_effort=${JsonPrimitive(it)}")) }
                addAll(listOf("exec", "--json"))
                if (session != null) addAll(listOf("resume", session))
                if (structured) addAll(listOf("--output-schema", requireNotNull(schemaPath)))
                imagePaths.forEach { addAll(listOf("--image", it)) }
                add("--"); add(prompt)
            }
            AgentId.CLAUDE_CODE -> buildList {
                addAll(listOf(executable, "-p", "--output-format", "stream-json", "--verbose"))
                if (structured) addAll(listOf("--json-schema", SkillGeneration.schema))
                request.reasoningLevel?.let { addAll(listOf("--effort", it)) }
                if (session != null) addAll(listOf("--resume", session))
                if (streamInput) addAll(listOf("--input-format", "stream-json"))
                else { add("--"); add(prompt) }
                if (approvals) addAll(listOf("--permission-prompt-tool", "stdio"))
            }
        }
    }
}
