package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*

/**
 * Phone Codex 0.155.1 app-server transport behind [AgentSession].
 * Outbound lines use the same events as `exec --json`, so the runtime has one decoder.
 * `thread/resume` is used only when this process is created for a saved session.
 */
class CodexAppServerSession(private val cwd: String, private val model: String, private val resumeThreadId: String?) : AgentSession {
    private val queue = Channel<ByteArray>(18)
    override val input = queue.receiveAsFlow()
    private var nextId = 1
    private var pending: String? = null
    private var step = Step.INITIALIZE
    private var queued: AgentTurn? = null
    private var turnOpen = false
    private var turnEnded = false
    private var finished = false
    private var sessionId: String? = null
    private val messageText = mutableMapOf<String, String>()
    private val reasoningSummary = mutableMapOf<String, String>()
    private val reasoningContent = mutableMapOf<String, String>()
    private val commandOutput = mutableMapOf<String, String>()
    private val commands = mutableMapOf<String, String>()
    init {
        require(cwd.startsWith("/") && '\u0000' !in cwd && model.isNotBlank() && '\u0000' !in model)
        require(resumeThreadId == null || resumeThreadId.matches(Regex("[A-Za-z0-9-]{1,100}")))
        send(id(), "initialize", buildJsonObject { putJsonObject("clientInfo") { put("name", "mobby"); put("version", "1") } })
    }
    override fun sessionId() = sessionId
    @Synchronized override fun submit(turn: AgentTurn) {
        check(!finished && !turnOpen && queued == null) { "Codex cannot accept another turn" }
        require(turn.images.all { it.path.startsWith("/") && '\u0000' !in it.path })
        queued = turn
        if (step == Step.READY) sendTurn()
    }
    @Synchronized override fun takeTurnEnded(): Boolean = turnEnded.also { if (it) turnEnded = false }
    @Synchronized override fun release() { if (!finished) queue.close() }
    override fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = false
    @Synchronized override fun onStdout(line: String, autoAllow: Boolean): List<String> {
        if (finished) return emptyList()
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return emptyList()
        val id = (value["id"] as? JsonPrimitive)?.contentOrNull
        if (id != null && (value.containsKey("result") || value.containsKey("error"))) {
            if (id != pending) return emptyList()
            pending = null
            if (value.containsKey("error")) return fail(value["error"].toString())
            return when (step) {
                Step.INITIALIZE -> {
                    enqueue(buildJsonObject { put("method", "initialized") })
                    sendThread()
                    emptyList()
                }
                Step.THREAD -> {
                    val threadId = value["result"]?.jsonObject?.get("thread")?.jsonObject?.text("id")
                        ?: error("Codex did not return a thread id")
                    check(threadId.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Codex thread id is not usable" }
                    sessionId = threadId
                    step = Step.READY
                    sendTurn()
                    listOf(event("thread.started") { put("thread_id", threadId) })
                }
                Step.TURN -> { step = Step.READY; emptyList() }
                Step.READY -> emptyList()
            }
        }
        val method = value.text("method") ?: return emptyList()
        val params = value["params"] as? JsonObject
        if (method == "turn/completed") {
            val turn = params?.get("turn") as? JsonObject
            val status = turn?.text("status")
            turnOpen = false
            turnEnded = true
            step = Step.READY
            pending = null
            return when (status) {
                null, "completed" -> listOf(event("turn.completed"))
                else -> listOf(event("turn.failed") { put("error", checkNotNull(turn).get("error") ?: JsonPrimitive(status)) })
            }
        }
        val item = params?.get("item")?.jsonObject
        return when (method) {
            "item/agentMessage/delta" -> {
                val itemId = params?.text("itemId") ?: return emptyList()
                val delta = params.text("delta").orEmpty()
                if (delta.isEmpty()) return emptyList()
                val text = messageText.getOrDefault(itemId, "") + delta
                messageText[itemId] = text
                listOf(event("item.updated") { putJsonObject("item") { put("id", itemId); put("type", "agent_message"); put("text", text) } })
            }
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded" -> {
                val itemId = params?.text("itemId") ?: return emptyList()
                val delta = params.text("delta").orEmpty()
                when (method) {
                    "item/reasoning/summaryTextDelta" -> reasoningSummary[itemId] = reasoningSummary[itemId].orEmpty() + delta
                    "item/reasoning/textDelta" -> reasoningContent[itemId] = reasoningContent[itemId].orEmpty() + delta
                    else -> if (reasoningSummary[itemId].orEmpty().isNotEmpty()) reasoningSummary[itemId] = reasoningSummary[itemId].orEmpty() + "\n\n"
                }
                val text = joinReasoning(reasoningSummary[itemId].orEmpty(), reasoningContent[itemId].orEmpty())
                if (text.isEmpty()) emptyList() else listOf(reasoningEvent(itemId, text))
            }
            "item/commandExecution/outputDelta" -> {
                val itemId = params?.text("itemId") ?: return emptyList()
                val delta = params.text("delta") ?: params.text("output") ?: return emptyList()
                if (delta.isEmpty()) return emptyList()
                val text = commandOutput.getOrDefault(itemId, "") + delta
                commandOutput[itemId] = text
                listOf(event("item.updated") { putJsonObject("item") {
                    put("id", itemId); put("type", "command_execution"); put("aggregated_output", text)
                    commands[itemId]?.let { put("command", it) }
                } })
            }
            "item/started", "item/completed" -> item?.let { listOf(event(if (method == "item/completed") "item.completed" else "item.started") { put("item", unifiedItem(it)) }) }.orEmpty()
            else -> emptyList()
        }
    }
    @Synchronized override fun close() { finished = true; queue.cancel() }
    private fun sendThread() {
        val resume = resumeThreadId != null
        send(id(), if (resume) "thread/resume" else "thread/start", buildJsonObject {
            if (resume) put("threadId", resumeThreadId)
            put("cwd", cwd); put("model", model); put("approvalPolicy", "never"); put("sandbox", "danger-full-access")
        })
        step = Step.THREAD
    }
    private fun sendTurn() {
        val turn = checkNotNull(queued) { "Codex turn started without a message" }
        val threadId = checkNotNull(sessionId) { "Codex turn started without a thread" }
        queued = null
        turnOpen = true
        turnEnded = false
        send(id(), "turn/start", buildJsonObject {
            put("threadId", threadId); put("cwd", cwd); put("model", model); put("approvalPolicy", "never")
            putJsonObject("sandboxPolicy") { put("type", "dangerFullAccess") }
            putJsonArray("input") {
                add(buildJsonObject { put("type", "text"); put("text", turn.prompt) })
                turn.images.forEach { image -> add(buildJsonObject { put("type", "localImage"); put("path", image.path) }) }
            }
            turn.outputSchema?.let { put("outputSchema", it) }
        })
        step = Step.TURN
    }
    private fun fail(message: String): List<String> {
        turnOpen = false
        turnEnded = true
        step = Step.READY
        queued = null
        return listOf(event("turn.failed") { putJsonObject("error") { put("message", message) } })
    }
    private fun unifiedItem(item: JsonObject): JsonObject = buildJsonObject {
        put("id", item.text("id") ?: "item")
        when (item.text("type")) {
            "agentMessage" -> { put("type", "agent_message"); put("text", item.text("text").orEmpty()) }
            "reasoning" -> {
                put("type", "reasoning")
                val id = item.text("id") ?: "item"
                val summary = partsText(item["summary"])
                val content = partsText(item["content"])
                if (summary.isNotEmpty()) reasoningSummary[id] = longer(reasoningSummary[id].orEmpty(), summary)
                if (content.isNotEmpty()) reasoningContent[id] = longer(reasoningContent[id].orEmpty(), content)
                put("text", joinReasoning(reasoningSummary[id].orEmpty(), reasoningContent[id].orEmpty()))
            }
            "commandExecution" -> {
                val id = item.text("id") ?: "item"
                val command = commandText(item["command"]).ifBlank { commands[id].orEmpty() }
                commands[id] = command
                put("type", "command_execution"); put("command", command)
                val output = commandResult(item)
                if (output.isNotEmpty()) {
                    commandOutput[id] = longer(commandOutput[id].orEmpty(), output)
                    put("aggregated_output", commandOutput[id].orEmpty())
                } else item["aggregatedOutput"]?.let { put("aggregated_output", it) }
                item["exitCode"]?.let { put("exit_code", it) }
                item["status"]?.let { put("status", it) }
            }
            "mcpToolCall" -> {
                put("type", "mcp_tool_call"); put("tool", item.text("tool") ?: "tool")
                item["arguments"]?.let { put("arguments", it) }
                item["result"]?.let { put("result", it) }
                item["status"]?.let { put("status", it) }
            }
            "fileChange" -> { put("type", "file_change"); item["changes"]?.let { put("changes", it) }; item["status"]?.let { put("status", it) } }
            "webSearch" -> { put("type", "web_search"); put("query", item.text("query").orEmpty()) }
            else -> { put("type", item.text("type") ?: "unknown"); put("raw", item) }
        }
    }
    private fun reasoningEvent(id: String, text: String) = event("item.updated") {
        putJsonObject("item") { put("id", id); put("type", "reasoning"); put("text", text) }
    }
    private fun joinReasoning(summary: String, content: String) = when {
        summary.isBlank() -> content
        content.isBlank() -> summary
        else -> summary.trimEnd() + "\n\n" + content.trimStart()
    }
    private fun longer(current: String, incoming: String) = when {
        incoming.startsWith(current) || current.isEmpty() -> incoming
        current.startsWith(incoming) -> current
        else -> incoming
    }
    private fun commandText(value: JsonElement?): String = when (value) {
        is JsonArray -> value.joinToString(" ") { element -> (element as? JsonPrimitive)?.contentOrNull.orEmpty() }.trim()
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        else -> ""
    }
    private fun commandResult(item: JsonObject): String {
        val aggregated = (item["aggregatedOutput"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (aggregated.isNotEmpty()) return aggregated
        return listOf("stdout", "stderr").map { (item[it] as? JsonPrimitive)?.contentOrNull.orEmpty() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
    private fun partsText(value: JsonElement?): String = when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.joinToString("\n\n") { partsText(it) }.trim()
        is JsonObject -> value.text("text") ?: value.text("summary") ?: ""
        else -> ""
    }
    private fun event(type: String, body: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject { put("type", type); body() }.toString()
    private fun send(id: String, method: String, params: JsonObject) {
        pending = id
        enqueue(buildJsonObject { put("id", id); put("method", method); put("params", params) })
    }
    private fun id() = (nextId++).toString()
    private fun enqueue(value: JsonObject) { check(queue.trySend((value.toString() + "\n").toByteArray()).isSuccess) { "Codex input queue unavailable" } }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private enum class Step { INITIALIZE, THREAD, TURN, READY }
}
