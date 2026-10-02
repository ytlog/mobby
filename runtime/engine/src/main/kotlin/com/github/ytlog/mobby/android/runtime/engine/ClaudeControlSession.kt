package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.util.UUID

/** Claude Code stream-json transport. Its stdin continues one session; a fresh session needs a new CLI process. */
class ClaudeControlSession : AgentSession {
    override val supportsInsertion = true
    private val initializeId = "initialize-${UUID.randomUUID()}"
    private val queue = Channel<ByteArray>(18)
    override val input = queue.receiveAsFlow()
    private var requestId = RequestId("")
    private var pendingTurn: AgentTurn? = null
    private var initialized = false
    private var finished = false
    private var turnOpen = false
    private var turnEnded = false
    private var sessionId: String? = null
    private val pending = mutableMapOf<String, JsonObject>()
    private val seen = mutableSetOf<String>()
    init {
        enqueue(buildJsonObject {
            put("type", "control_request"); put("request_id", initializeId)
            putJsonObject("request") { put("subtype", "initialize"); put("hooks", JsonNull) }
        })
    }
    override fun sessionId() = sessionId
    @Synchronized override fun submit(turn: AgentTurn) {
        check(!finished && !turnOpen && pendingTurn == null && pending.isEmpty()) { "CLI cannot accept another message" }
        requestId = turn.requestId
        if (!initialized) { pendingTurn = turn; return }
        turnEnded = false
        turnOpen = true
        enqueue(turn.claudeWireMessage())
    }
    @Synchronized override fun takeTurnEnded(): Boolean = turnEnded.also { if (it) turnEnded = false }
    @Synchronized override fun insert(text: String): Boolean {
        if (finished || !initialized || !turnOpen || text.isBlank()) return false
        val message = AgentTurn(RequestId(UUID.randomUUID().toString()), text).claudeWireMessage()
        return queue.trySend((message.toString() + "\n").toByteArray()).isSuccess
    }
    @Synchronized override fun release() { if (!finished) queue.close() }
    @Synchronized override fun onStdout(line: String, autoAllow: Boolean): List<String> {
        if (finished) return emptyList()
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return listOf(line)
        value.text("session_id")?.let { if (sessionId == null) sessionId = it }
        when (value.text("type")) {
            "control_response" -> {
                val response = value["response"]?.jsonObject ?: error("Missing CLI initialization response")
                check(!initialized && response.text("request_id") == initializeId && response.text("subtype") == "success") { "CLI initialization failed" }
                initialized = true
                val turn = checkNotNull(pendingTurn) { "CLI initialized before a turn was submitted" }
                pendingTurn = null
                turnOpen = true
                enqueue(turn.claudeWireMessage())
                return emptyList()
            }
            "control_request" -> {
                check(initialized) { "CLI requested permission before initialization" }
                val fact = ProtocolDecoder(AgentId.CLAUDE_CODE).decode(line).filterIsInstance<AgentFact.Approval>().singleOrNull()
                    ?: error("Unsupported CLI control request")
                val request = value.getValue("request").jsonObject
                pending[fact.id]?.let { check(it == request) { "Conflicting CLI permission request" }; return emptyList() }
                check(fact.id !in seen && seen.size < 512 && pending.size < 16) { "Reused or excessive CLI permission requests" }
                pending[fact.id] = request
                seen += fact.id
                if (autoAllow && offer(requestId, fact.id, ApprovalChoice.ALLOW_ONCE)) return emptyList()
            }
            "result" -> {
                if (!turnOpen) return emptyList()
                check(initialized && pending.isEmpty()) { "CLI completed with unresolved permissions" }
                turnOpen = false
                turnEnded = true
            }
        }
        return listOf(line)
    }
    @Synchronized override fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean {
        if (requestId != this.requestId || finished || !initialized) return false
        val request = pending[approvalId] ?: return false
        val reply = buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success"); put("request_id", approvalId)
                putJsonObject("response") {
                    when (choice) {
                        ApprovalChoice.ALLOW_ONCE -> { put("behavior", "allow"); put("updatedInput", request.getValue("input")) }
                        ApprovalChoice.DENY -> { put("behavior", "deny"); put("message", "User denied this operation") }
                    }
                }
            }
        }
        if (!queue.trySend((reply.toString() + "\n").toByteArray()).isSuccess) return false
        pending.remove(approvalId)
        return true
    }
    @Synchronized override fun close() { finished = true; pending.clear(); seen.clear(); queue.cancel() }
    private fun enqueue(value: JsonObject) { check(queue.trySend((value.toString() + "\n").toByteArray()).isSuccess) { "CLI input queue unavailable" } }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
