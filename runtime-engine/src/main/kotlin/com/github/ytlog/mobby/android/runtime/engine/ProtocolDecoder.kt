package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.serialization.json.*

/** Public CLI messages, tool output, and reasoning text. Reasoning stays on its thinking step. A reasoning block that is only a misplaced final answer is shown as the reply. */
sealed interface AgentFact {
    data class Session(val id: String) : AgentFact
    data class Text(val messageId: String, val text: String) : AgentFact
    data class Proposal(val markdown: String) : AgentFact
    /** `body` is null when a later result must not replace the step's type. */
    data class Tool(val id: String, val body: StepBody?, val output: String? = null, val outcome: ToolOutcome? = null) : AgentFact
    data class Diagnostic(val kind: String, val text: String) : AgentFact
    data class Completed(val success: Boolean, val error: ErrorCode? = null) : AgentFact
    data class Approval(val id: String, val subject: ApprovalSubject) : AgentFact
    object InvalidApproval : AgentFact
}

class ProtocolDecoder(private val agent: AgentId, private val requestedOutput: RequestedOutput = RequestedOutput.TEXT) {
    private var skillResult: SkillGeneration.Result? = null
    private val structuredTools = mutableSetOf<String>()
    private var lastAssistant = ""
    private var fallbackId = 0
    private var streamMessageId: String? = null
    private val streamedText = mutableSetOf<String>()
    private val streamedParts = sortedMapOf<Int, StringBuilder>()
    private val startedTools = mutableSetOf<String>()
    private val codexText = mutableMapOf<String, String>()
    private val reasoningText = mutableMapOf<String, String>()
    private val thinkingText = mutableMapOf<String, String>()
    private val toolOutput = mutableMapOf<String, String>()
    private val commands = mutableMapOf<String, String>()
    private val inputCaptured = mutableSetOf<String>()
    private var thinkingStep: String? = null
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
        return when (agent) {
            AgentId.CODEX -> codex(value, line)
            AgentId.CLAUDE_CODE -> claude(value, line)
            AgentId.OPEN_CODE -> opencode(value, line)
        }
    }
    private fun opencode(value: JsonObject, line: String): List<AgentFact> = buildList {
        value.text("sessionID")?.takeIf { AgentSessionId.matches(it) }?.let { add(AgentFact.Session(it)) }
        when (value.text("type")) {
            "step_start" -> Unit
            "text" -> {
                val part = value["part"] as? JsonObject
                if (part == null) { add(AgentFact.Diagnostic("invalid-text", line)); return@buildList }
                val text = part.text("text").orEmpty()
                val id = part.text("id") ?: "text-${fallbackId++}"
                if (text.isEmpty()) return@buildList
                if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                    skillResult = SkillGeneration.parse(text)
                    add(AgentFact.Text(id, skillResult?.message ?: text))
                } else add(AgentFact.Text(id, text))
            }
            "reasoning" -> {
                val part = value["part"] as? JsonObject
                val id = part?.text("id") ?: "thinking-${fallbackId++}"
                add(AgentFact.Tool(id, StepBody.Thinking, part?.text("text")?.takeIf { it.isNotEmpty() }, ToolOutcome.SUCCEEDED))
            }
            "tool_use" -> {
                val part = value["part"] as? JsonObject
                if (part == null) { add(AgentFact.Diagnostic("invalid-tool", line)); return@buildList }
                val state = part["state"] as? JsonObject
                val id = part.text("id") ?: part.text("callID") ?: "tool-${fallbackId++}"
                val name = part.text("tool") ?: "tool"
                val output = when (val raw = state?.get("output") ?: state?.get("error")) {
                    null -> null
                    is JsonPrimitive -> raw.contentOrNull
                    else -> raw.toString()
                }
                val outcome = when (state?.text("status")) {
                    "completed" -> ToolOutcome.SUCCEEDED
                    "error" -> ToolOutcome.FAILED
                    else -> null
                }
                add(toolFact(id, name, state?.get("input"), output, outcome))
            }
            "step_finish" -> {
                val reason = (value["part"] as? JsonObject)?.text("reason")
                when (reason) {
                    "stop" -> if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) addAll(finishSkill(skillResult)) else add(AgentFact.Completed(true))
                    "error" -> add(AgentFact.Completed(false, ErrorCode.PROTOCOL_ERROR))
                }
            }
            "error" -> {
                add(AgentFact.Diagnostic("error", value["error"]?.toString() ?: "OpenCode error"))
                add(AgentFact.Completed(false, ErrorCode.PROTOCOL_ERROR))
            }
            else -> add(AgentFact.Diagnostic(value.text("type") ?: "unknown", line))
        }
    }
    private fun codex(value: JsonObject, line: String): List<AgentFact> {
        return when (value.text("type")) {
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
                "reasoning" -> reasoningFacts(id, item.text("text").orEmpty(), completed)
                "agent_message" -> {
                    val text = item.text("text").orEmpty()
                    if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                        if (!completed) emptyList() else {
                            skillResult = SkillGeneration.parse(text)
                            listOf(AgentFact.Text(id, skillResult?.message ?: text))
                        }
                    } else {
                        val previous = codexText[id].orEmpty()
                        val delta = when {
                            text.isEmpty() -> ""
                            previous.isEmpty() -> text
                            text.startsWith(previous) -> text.removePrefix(previous)
                            previous.startsWith(text) -> ""
                            else -> text
                        }
                        codexText[id] = when {
                            text.isEmpty() -> previous
                            previous.isEmpty() || text.startsWith(previous) -> text
                            previous.startsWith(text) -> previous
                            else -> previous + text
                        }
                        if (delta.isEmpty()) emptyList() else listOf(AgentFact.Text(id, delta))
                    }
                }
                "command_execution", "file_change", "mcp_tool_call", "web_search", "todo_list" -> {
                    val outcome = if (!completed) null else if (item.text("status") == "failed" || item.text("status") == "declined" ||
                        (item["exit_code"] as? JsonPrimitive)?.intOrNull?.let { it != 0 } == true) ToolOutcome.FAILED else ToolOutcome.SUCCEEDED
                    when (type) {
                        "command_execution" -> {
                            val command = unwrapShell(commands[id] ?: commandText(item["command"]))
                            if (command.isNotBlank()) commands[id] = command
                            listOf(AgentFact.Tool(id, StepBody.Command(commands[id] ?: command), freshOutput(id, commandOutput(item)), outcome))
                        }
                        "file_change" -> {
                            val (body, diff) = fileChange(item["changes"])
                            listOf(AgentFact.Tool(id, body, freshOutput(id, diff), outcome))
                        }
                        "mcp_tool_call" -> listOf(toolFact(id, item.text("tool") ?: "mcp_tool_call", item["arguments"], commandOutput(item), outcome))
                        "web_search" -> listOf(AgentFact.Tool(id, StepBody.Action("web_search", item.text("query").orEmpty().fit()), freshOutput(id, commandOutput(item)), outcome))
                        else -> listOf(AgentFact.Tool(id, StepBody.Action("todo_list", actionDetail(item["items"])), freshOutput(id, commandOutput(item)), outcome))
                    }
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
                    val subject = approvalSubject(tool, input)
                    if (subject != null) add(AgentFact.Approval(id, subject))
                    else {
                        add(AgentFact.Diagnostic("invalid-approval", "无法完整解析 CLI 审批请求，已停止授权流程"))
                        add(AgentFact.InvalidApproval)
                    }
                } else {
                    add(AgentFact.Diagnostic("invalid-approval", "无法完整解析 CLI 审批请求，已停止授权流程"))
                    add(AgentFact.InvalidApproval)
                }
            }
            "system" -> if (value.text("subtype") != "init") add(AgentFact.Diagnostic(value.text("subtype") ?: "system", line))
            "stream_event" -> addAll(streamEvent(value))
            "assistant", "user" -> {
                val message = value["message"] as? JsonObject
                val id = message?.text("id") ?: streamMessageId ?: "message-${fallbackId++}"
                streamMessageId = id
                val blocks = message?.get("content") as? JsonArray ?: return@buildList
                val thinkingIds = blocks.mapIndexedNotNull { index, element ->
                    val block = element as? JsonObject ?: return@mapIndexedNotNull null
                    val type = block.text("type")
                    if (type == "thinking" || type == "redacted_thinking") "thinking:$id#$index" else null
                }
                if (thinkingStep !in thinkingIds) addAll(finishThinking())
                val texts = mutableListOf<String>()
                for (index in blocks.indices) {
                    val block = blocks[index] as? JsonObject ?: continue
                    if (requestedOutput == RequestedOutput.SKILL_PROPOSAL) {
                        if (block.text("type") == "tool_use" && block.text("name") == "StructuredOutput") {
                            block.text("id")?.let { structuredTools.add(it) }; continue
                        }
                        if (block.text("type") == "tool_result" && block.text("tool_use_id") in structuredTools) continue
                    }
                    val textId = if (index == 0) id else "$id#$index"
                    when (block.text("type")) {
                    "text" -> {
                        val text = block.text("text").orEmpty()
                        if (text.isNotEmpty() && value.text("type") == "assistant") {
                            texts += text
                            rememberAssistantText(textId, index, text) { add(it) }
                        }
                    }
                    "tool_use" -> add(toolFact(block.text("id") ?: "tool-${fallbackId++}", block.text("name") ?: "tool", block["input"], null, null))
                    "tool_result" -> {
                        val toolId = block.text("tool_use_id") ?: "tool-${fallbackId++}"
                        val raw = block["content"]?.let { content -> if (content is JsonPrimitive) content.contentOrNull else content.toString() }
                        val outcome = if ((block["is_error"] as? JsonPrimitive)?.booleanOrNull == true) ToolOutcome.FAILED else ToolOutcome.SUCCEEDED
                        add(AgentFact.Tool(toolId, null, if (toolId in inputCaptured) null else freshOutput(toolId, raw), outcome))
                    }
                    "image" -> add(AgentFact.Diagnostic("image", "图片输入（内容不写入诊断日志）"))
                    "thinking", "redacted_thinking" -> {
                        val thinkingId = "thinking:$id#$index"
                        startedTools.add(thinkingId)
                        if (thinkingStep == thinkingId) thinkingStep = null
                        add(thinkingUpdate(thinkingId, block.text("thinking").orEmpty(), ToolOutcome.SUCCEEDED))
                    }
                    else -> add(AgentFact.Diagnostic(block.text("type") ?: "unknown-content", block.toString()))
                    }
                }
                if (texts.isNotEmpty()) lastAssistant = streamedParts.values.joinToString("\n") { it.toString() }
            }
            "result" -> {
                addAll(finishThinking())
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
    private fun streamEvent(value: JsonObject): List<AgentFact> {
        val event = value["event"] as? JsonObject ?: return emptyList()
        return when (event.text("type")) {
            "message_start" -> {
                streamMessageId = (event["message"] as? JsonObject)?.text("id") ?: streamMessageId
                emptyList()
            }
            "content_block_start" -> {
                val block = event["content_block"] as? JsonObject ?: return finishThinking()
                val index = (event["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val messageId = streamMessageId ?: "message-$fallbackId".also { streamMessageId = it }
                when (block.text("type")) {
                    "tool_use" -> {
                        val toolId = block.text("id") ?: return finishThinking()
                        val name = block.text("name") ?: "tool"
                        val tool = if (startedTools.add(toolId)) listOf(toolFact(toolId, name, block["input"], null, null)) else emptyList()
                        finishThinking() + tool
                    }
                    "thinking", "redacted_thinking" -> finishThinking() + beginThinking("thinking:$messageId#$index")
                    else -> finishThinking()
                }
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? JsonObject ?: return emptyList()
                if (delta.text("type") == "thinking_delta") {
                    val id = thinkingStep ?: return emptyList()
                    val text = delta.text("thinking").orEmpty()
                    return if (text.isEmpty()) emptyList() else listOf(thinkingUpdate(id, text, null))
                }
                if (delta.text("type") != "text_delta") return emptyList()
                val text = delta.text("text").orEmpty()
                if (text.isEmpty()) return finishThinking()
                val index = (event["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val messageId = streamMessageId ?: "message-$fallbackId".also { streamMessageId = it }
                val id = if (index == 0) messageId else "$messageId#$index"
                streamedText += id
                streamedParts.getOrPut(index) { StringBuilder() }.append(text)
                lastAssistant = streamedParts.values.joinToString("\n") { it.toString() }
                finishThinking() + listOf(AgentFact.Text(id, text))
            }
            else -> emptyList()
        }
    }
    private fun commandOutput(item: JsonObject): String? {
        val aggregated = item.text("aggregated_output")
        if (!aggregated.isNullOrEmpty()) return aggregated
        val combined = listOf(item.text("stdout"), item.text("stderr")).filter { !it.isNullOrEmpty() }.joinToString("\n")
        if (combined.isNotEmpty()) return combined
        if (aggregated != null) return null
        return item.text("result") ?: item["error"]?.toString()?.takeIf { it != "null" } ?: item["items"]?.toString()?.takeIf { it != "null" }
    }
    private fun fileChange(changes: JsonElement?): Pair<StepBody.FileDiff, String?> {
        val array = changes as? JsonArray ?: return StepBody.FileDiff(emptyList()) to null
        val parsed = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj.text("path") ?: obj.text("file_path") ?: return@mapNotNull null
            path.fit() to obj.text("diff").orEmpty()
        }
        val text = parsed.joinToString("\n\n") { (path, diff) -> if (parsed.size == 1) diff else "$path\n$diff" }.ifBlank { null }
        return StepBody.FileDiff(parsed.map { it.first }) to text
    }
    private fun toolFact(id: String, name: String, input: JsonElement?, output: String?, outcome: ToolOutcome?): AgentFact.Tool {
        val body = conversationBody(name, input)
        val captured = capturedText(body, input)
        if (captured != null) inputCaptured += id
        val text = when {
            captured != null -> captured
            id in inputCaptured -> null
            else -> output
        }
        return AgentFact.Tool(id, body, freshOutput(id, text), outcome)
    }
    private fun conversationBody(name: String, input: JsonElement?): StepBody {
        val key = name.substringAfterLast("__").lowercase()
        val obj = input as? JsonObject
        val path = field(obj, "file_path", "path", "file", "notebook_path").fit()
        return when (key) {
            "thinking", "redacted_thinking" -> StepBody.Thinking
            "bash", "shell", "command_execution" -> StepBody.Command(unwrapShell(field(obj, "command", "cmd").ifBlank { (input as? JsonPrimitive)?.contentOrNull.orEmpty() }).fit())
            "read" -> StepBody.FileRead(path)
            "write" -> StepBody.FileWrite(path)
            "edit", "editnotebook", "apply_patch" -> StepBody.FileDiff(listOf(path).filter { it.isNotEmpty() })
            else -> StepBody.Action(name.ifBlank { "tool" }.take(256), actionDetail(input))
        }
    }
    private fun approvalSubject(name: String, input: JsonObject): ApprovalSubject? {
        val body = conversationBody(name, input)
        val allowed = when (body) {
            StepBody.Thinking -> return null
            is StepBody.Command -> setOf("command", "cmd", "description")
            is StepBody.FileRead -> setOf("file_path", "path", "file", "notebook_path", "offset", "limit", "description")
            is StepBody.FileWrite -> setOf("file_path", "path", "file", "notebook_path", "content", "description")
            is StepBody.FileDiff -> setOf("file_path", "path", "file", "notebook_path", "old_string", "old_str", "new_string", "new_str", "description")
            is StepBody.Action -> input.keys.filter { textOf(input[it]) != null }.toSet() + "description"
        }
        if (input.keys.any { it !in allowed }) return null
        return when (body) {
            StepBody.Thinking -> null
            is StepBody.Command -> ApprovalSubject.Command(body.command)
            is StepBody.FileRead -> ApprovalSubject.FileRead(body.path, field(input, "offset"), field(input, "limit"))
            is StepBody.FileWrite -> ApprovalSubject.FileWrite(body.path, field(input, "content").fit())
            is StepBody.FileDiff -> ApprovalSubject.FileDiff(body.paths, capturedText(body, input).orEmpty())
            is StepBody.Action -> ApprovalSubject.Action(body.name, body.detail)
        }
    }
    private fun capturedText(body: StepBody, input: JsonElement?): String? {
        val obj = input as? JsonObject ?: return null
        return when (body) {
            is StepBody.FileWrite -> field(obj, "content").ifBlank { null }
            is StepBody.FileDiff -> {
                val old = field(obj, "old_string", "old_str")
                val new = field(obj, "new_string", "new_str")
                if (old.isBlank() && new.isBlank()) null else snippetDiff(body.paths.firstOrNull().orEmpty(), old, new)
            }
            else -> null
        }
    }
    private fun actionDetail(input: JsonElement?): String = when (input) {
        null -> ""
        is JsonPrimitive -> input.contentOrNull.orEmpty().fit()
        is JsonArray -> input.mapNotNull { textOf(it) }.joinToString("\n").ifBlank { input.toString() }.fit()
        is JsonObject -> {
            val preferred = listOf("query", "pattern", "glob", "url", "description", "prompt", "text", "command", "cmd")
            val picked = preferred.mapNotNull { key -> textOf(input[key]) }
            when {
                picked.size == 1 && input.size == 1 -> picked.single().fit()
                picked.isNotEmpty() -> picked.joinToString("\n").fit()
                else -> input.entries.mapNotNull { (key, value) -> textOf(value)?.let { "$key\n$it" } }.joinToString("\n\n").fit()
            }
        }
    }
    private fun field(obj: JsonObject?, vararg keys: String): String {
        if (obj == null) return ""
        return keys.firstNotNullOfOrNull { key -> textOf(obj[key]) }.orEmpty()
    }
    private fun textOf(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> value.mapNotNull { textOf(it) }.joinToString(" ").takeIf { it.isNotBlank() }
        else -> null
    }
    private fun commandText(value: JsonElement?): String = when (value) {
        is JsonArray -> value.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.trim()
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }
    private fun unwrapShell(command: String): String {
        val matched = Regex("""^(?:\S*/)?(?:bash|sh)\s+-lc\s+([\s\S]*)$""").find(command.trim()) ?: return command.trim()
        return unquote(matched.groupValues[1].trim())
    }
    private fun unquote(value: String): String {
        if (value.length < 2 || value.first() != value.last() || value.first() !in listOf('\'', '"')) return value
        val inner = value.substring(1, value.length - 1)
        return if (value.first() == '"') inner.replace("\\n", "\n").replace("\\\"", "\"") else inner
    }
    private fun snippetDiff(path: String, old: String, new: String): String = buildString {
        val name = path.ifBlank { "file" }
        append("--- ").append(name).append('\n')
        append("+++ ").append(name).append('\n')
        old.lines().forEach { append("- ").append(it).append('\n') }
        new.lines().forEach { append("+ ").append(it).append('\n') }
    }.trimEnd()
    private fun String.fit() = if (length <= 65536) this else take(65536) + "\n…已截断"
    private fun freshOutput(id: String, incoming: String?): String? {
        if (incoming.isNullOrEmpty()) return null
        val previous = toolOutput[id].orEmpty()
        val delta = when {
            incoming == previous -> return null
            previous.isEmpty() -> incoming
            incoming.startsWith(previous) -> incoming.removePrefix(previous)
            else -> return null
        }
        toolOutput[id] = incoming
        return delta
    }
    private fun reasoningFacts(id: String, incoming: String, completed: Boolean): List<AgentFact> {
        val previous = reasoningText[id].orEmpty()
        val full = when {
            incoming.isEmpty() -> previous
            previous.isEmpty() || incoming.startsWith(previous) -> incoming
            previous.startsWith(incoming) -> previous
            else -> previous + incoming
        }
        reasoningText[id] = full
        val answer = reasoningAnswer(full)
        val openedAnswer = full.trimStart().let { it.startsWith("<arg_value>") || it.isNotEmpty() && "<arg_value>".startsWith(it) }
        if (!completed && openedAnswer) return emptyList()
        if (answer != null) return textDelta(id, answer)
        val shown = thinkingText[id].orEmpty()
        val delta = when {
            full == shown -> ""
            shown.isEmpty() || full.startsWith(shown) -> full.removePrefix(shown)
            else -> full
        }
        if (delta.isNotEmpty()) thinkingText[id] = if (shown.isEmpty() || full.startsWith(shown)) full else shown + delta
        if (delta.isEmpty() && !completed && id in startedTools) return emptyList()
        startedTools.add(id)
        return listOf(AgentFact.Tool(id, StepBody.Thinking, delta.ifEmpty { null }, if (completed) ToolOutcome.SUCCEEDED else null))
    }
    private fun textDelta(id: String, full: String): List<AgentFact> {
        val previous = codexText[id].orEmpty()
        val delta = when {
            full.isEmpty() -> ""
            previous.isEmpty() -> full
            full.startsWith(previous) -> full.removePrefix(previous)
            previous.startsWith(full) -> ""
            else -> full
        }
        codexText[id] = when {
            full.isEmpty() -> previous
            previous.isEmpty() || full.startsWith(previous) -> full
            previous.startsWith(full) -> previous
            else -> previous + full
        }
        return if (delta.isEmpty()) emptyList() else listOf(AgentFact.Text(id, delta))
    }
    private fun reasoningAnswer(text: String): String? {
        val trimmed = text.trimStart()
        val open = "<arg_value>"
        if (!trimmed.startsWith(open)) return null
        val body = trimmed.removePrefix(open).removeSuffix("</arg_value>").trimEnd()
        return body.ifEmpty { null }
    }
    private fun thinkingUpdate(id: String, incoming: String, outcome: ToolOutcome?): AgentFact.Tool {
        val previous = thinkingText[id].orEmpty()
        val full = when {
            incoming.isEmpty() -> previous
            previous.isEmpty() || incoming.startsWith(previous) -> incoming
            previous.startsWith(incoming) -> previous
            else -> previous + incoming
        }
        val delta = if (full.startsWith(previous)) full.removePrefix(previous) else ""
        thinkingText[id] = full
        return AgentFact.Tool(id, StepBody.Thinking, delta.ifEmpty { null }, outcome)
    }
    private fun beginThinking(id: String): List<AgentFact> {
        if (!startedTools.add(id)) return emptyList()
        thinkingStep = id
        return listOf(AgentFact.Tool(id, StepBody.Thinking))
    }
    private fun finishThinking(): List<AgentFact> {
        val id = thinkingStep ?: return emptyList()
        thinkingStep = null
        return listOf(AgentFact.Tool(id, StepBody.Thinking, outcome = ToolOutcome.SUCCEEDED))
    }
    private fun rememberAssistantText(textId: String, index: Int, text: String, emit: (AgentFact) -> Unit) {
        val known = streamedParts.values.joinToString("\n") { it.toString() }
        val already = text == known || streamedParts.values.any { it.toString() == text }
        when {
            textId in streamedText -> streamedParts[index] = StringBuilder(text)
            already -> streamedText += textId
            else -> {
                emit(AgentFact.Text(textId, text))
                streamedText += textId
                streamedParts[index] = StringBuilder(text)
            }
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
        require(request.agentId == AgentId.CODEX || request.agentId == AgentId.OPEN_CODE || imagePaths.isEmpty())
        require(request.agentId == AgentId.CLAUDE_CODE || !streamInput)
        require(!approvals || request.agentId == AgentId.CLAUDE_CODE && streamInput)
        val structured = request.requestedOutput == RequestedOutput.SKILL_PROPOSAL
        require(schemaPath == null || structured && request.agentId == AgentId.CODEX && schemaPath.startsWith("/") && '\u0000' !in schemaPath)
        val session = request.sessionRef?.value
        require(session == null || AgentSessionId.matches(session))
        return when (request.agentId) {
            AgentId.CODEX -> buildList {
                // Phone Codex 0.155.1 exec accepts one prompt and exits. A live app-server
                // takes later turns on the same stdin. The prompt itself is not an argument.
                addAll(listOf(executable, "--sandbox", "danger-full-access",
                    "-c", "approval_policy=\"never\""))
                request.reasoningLevel?.let { addAll(listOf("-c", "model_reasoning_effort=${JsonPrimitive(it)}")) }
                addAll(listOf("app-server", "--listen", "stdio://"))
            }
            AgentId.CLAUDE_CODE -> buildList {
                addAll(listOf(executable, "-p", "--output-format", "stream-json", "--include-partial-messages", "--verbose"))
                if (structured) addAll(listOf("--json-schema", SkillGeneration.schema))
                request.reasoningLevel?.let { addAll(listOf("--effort", it)) }
                if (session != null) addAll(listOf("--resume", session))
                if (streamInput) addAll(listOf("--input-format", "stream-json"))
                else { add("--"); add(prompt) }
                if (approvals) addAll(listOf("--permission-prompt-tool", "stdio"))
            }
            AgentId.OPEN_CODE -> buildList {
                // One prompt per process. The next message cold-starts with --session.
                add(executable); add("run"); add("--format"); add("json"); add("--pure"); add("--auto")
                request.reasoningLevel?.let { add("--variant"); add(it) }
                if (session != null) addAll(listOf("--session", session))
                imagePaths.forEach { addAll(listOf("--file", it)) }
                add("-m"); add("openai/${request.modelId}")
                add("--"); add(prompt)
            }
        }
    }
}
