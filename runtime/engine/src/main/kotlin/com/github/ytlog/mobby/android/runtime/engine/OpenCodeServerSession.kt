package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.ApprovalChoice
import com.github.ytlog.mobby.android.runtime.api.RequestId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*

/** Commands the long-lived OpenCode server adapter over the gateway process' stdin. */
class OpenCodeServerSession(private val model: String, initialSession: String?) : AgentSession {
    private val queue = Channel<ByteArray>(18)
    override val input = queue.receiveAsFlow()
    private var sessionId = initialSession
    private var turnOpen = false
    private var turnEnded = false
    private var finished = false
    override fun sessionId() = sessionId
    @Synchronized override fun submit(turn: AgentTurn) {
        check(!finished && !turnOpen) { "OpenCode cannot accept another turn" }
        require(turn.images.all { it.path.startsWith("/") && '\u0000' !in it.path })
        val command = buildJsonObject {
            put("type", "prompt"); put("requestId", turn.requestId.value); put("text", turn.prompt); put("model", model)
            sessionId?.let { put("sessionId", it) }
            putJsonArray("images") { turn.images.forEach { image ->
                add(buildJsonObject { put("path", image.path); put("mediaType", image.mediaType) })
            } }
        }
        check(queue.trySend((command.toString() + "\n").toByteArray()).isSuccess) { "OpenCode input queue unavailable" }
        turnOpen = true; turnEnded = false
    }
    override fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = false
    @Synchronized override fun takeTurnEnded(): Boolean = turnEnded.also { if (it) turnEnded = false }
    @Synchronized override fun release() { if (!finished) queue.close() }
    @Synchronized override fun close() { finished = true; queue.cancel() }
    @Synchronized override fun onStdout(line: String, autoAllow: Boolean): List<String> {
        if (finished) return emptyList()
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        value?.text("sessionID")?.takeIf { AgentSessionId.matches(it) }?.let { sessionId = it }
        val type = value?.text("type")
        val reason = (value?.get("part") as? JsonObject)?.text("reason")
        if (turnOpen && (type == "error" || type == "step_finish" && (reason == "stop" || reason == "error"))) {
            turnOpen = false; turnEnded = true
        }
        return listOf(line)
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
