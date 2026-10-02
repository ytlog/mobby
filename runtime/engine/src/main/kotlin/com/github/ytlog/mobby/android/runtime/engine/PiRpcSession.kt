package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.util.UUID

/** Pi 0.87.1 RPC. Prompt acceptance and agent_end are not terminal outcomes. */
class PiRpcSession(initialSession: String? = null) : AgentSession {
    val launchSession = initialSession ?: UUID.randomUUID().toString()
    private val queue = Channel<ByteArray>(18)
    override val input = queue.receiveAsFlow()
    override val supportsInsertion = true
    override val supportsNewSession = true
    private var savedSession: String? = null
    private var ready = false
    private var turn: AgentTurn? = null
    private var turnOpen = false
    private var turnEnded = false
    private var finished = false
    private var nextId = 0
    private val pending = mutableMapOf<String, String>()
    private var newSessionFrom: String? = null
    override fun sessionId() = savedSession

    @Synchronized override fun submit(turn: AgentTurn) {
        check(!finished && !turnOpen) { "Pi cannot accept another turn" }
        this.turn = turn
        turnOpen = true; turnEnded = false
        if (ready) prompt(turn) else command("get_state")
    }
    @Synchronized override fun startNewSession(turn: AgentTurn): Boolean {
        val previous = savedSession ?: return false
        if (finished || turnOpen || !ready || pending.isNotEmpty()) return false
        this.turn = turn
        turnOpen = true; turnEnded = false
        ready = false; savedSession = null; newSessionFrom = previous
        command("new_session")
        return true
    }
    @Synchronized override fun insert(text: String): Boolean {
        if (finished || !ready || !turnOpen || text.isBlank()) return false
        command("steer") { put("message", text) }
        return true
    }
    override fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = false
    @Synchronized override fun takeTurnEnded() = turnEnded.also { if (it) turnEnded = false }
    @Synchronized override fun release() { finished = true; queue.close() }
    @Synchronized override fun close() { finished = true; queue.cancel() }

    @Synchronized override fun onStdout(line: String, autoAllow: Boolean): List<String> {
        if (finished) return emptyList()
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return fail("Invalid Pi RPC record")
        when (value.text("type")) {
            "response" -> {
                val expected = pending.remove(value.text("id")) ?: return fail("Uncorrelated Pi RPC response")
                if (value.text("command") != expected || value["success"]?.jsonPrimitive?.booleanOrNull != true)
                    return fail(value.text("error") ?: "Pi RPC command failed")
                if (expected == "new_session") {
                    if ((value["data"] as? JsonObject)?.get("cancelled")?.jsonPrimitive?.booleanOrNull != false)
                        return fail("Pi did not create a new session")
                    command("get_state")
                    return emptyList()
                }
                if (expected == "get_state") {
                    val id = (value["data"] as? JsonObject)?.text("sessionId")
                    val previous = newSessionFrom
                    if (id == null || !AgentSessionId.matches(id) ||
                        (previous == null && id != launchSession) || (previous != null && id == previous))
                        return fail("Pi session identity mismatch")
                    newSessionFrom = null
                    savedSession = id; ready = true
                    prompt(requireNotNull(turn))
                    return listOf(buildJsonObject { put("type", "mobby.pi.session"); put("id", id) }.toString())
                }
                return emptyList()
            }
            "agent_settled" -> {
                if (!turnOpen) return emptyList()
                turnOpen = false; turnEnded = true; turn = null
            }
            "extension_ui_request" -> return fail("Pi extension UI is unsupported")
        }
        if (newSessionFrom != null) return emptyList()
        return listOf(line)
    }
    private fun prompt(turn: AgentTurn) = command("prompt") {
        put("message", turn.prompt)
        if (turn.images.isNotEmpty()) putJsonArray("images") {
            turn.images.forEach { image -> add(buildJsonObject {
                put("type", "image"); put("mimeType", image.mediaType); put("data", image.base64)
            }) }
        }
    }
    private fun command(type: String, body: JsonObjectBuilder.() -> Unit = {}) {
        val id = "mobby-${nextId++}"
        val value = buildJsonObject { put("id", id); put("type", type); body() }
        check(queue.trySend((value.toString() + "\n").toByteArray()).isSuccess) { "Pi input queue unavailable" }
        pending[id] = type
    }
    private fun fail(message: String): List<String> {
        turnOpen = false; turnEnded = true; turn = null
        ready = false; savedSession = null
        newSessionFrom = null
        return listOf(buildJsonObject { put("type", "mobby.pi.error"); put("message", message) }.toString())
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
