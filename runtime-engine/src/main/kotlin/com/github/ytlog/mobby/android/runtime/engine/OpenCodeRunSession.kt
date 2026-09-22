package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.ApprovalChoice
import com.github.ytlog.mobby.android.runtime.api.RequestId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenCode 1.18 `run --format json` accepts one prompt on argv and then exits.
 * Stdin is closed immediately so a pipe is not appended to the message.
 * `step_finish` with reason stop is the terminal event; the process has been observed to keep running after it.
 */
class OpenCodeRunSession : AgentSession {
    private val queue = Channel<ByteArray>(1)
    override val input = queue.receiveAsFlow()
    private var turnEnded = false
    init { queue.close() }
    override fun sessionId(): String? = null
    override fun abandonAfterTurn() = true
    override fun submit(turn: AgentTurn) {}
    override fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice) = false
    override fun takeTurnEnded(): Boolean = turnEnded.also { if (it) turnEnded = false }
    override fun release() {}
    override fun close() { queue.close() }
    override fun onStdout(line: String, autoAllow: Boolean): List<String> {
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        val type = value?.text("type")
        val reason = (value?.get("part") as? JsonObject)?.text("reason")
        if (type == "error" || type == "step_finish" && (reason == "stop" || reason == "error")) turnEnded = true
        return listOf(line)
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
