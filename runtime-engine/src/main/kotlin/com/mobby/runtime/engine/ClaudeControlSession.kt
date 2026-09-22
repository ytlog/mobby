package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.UUID

/** One live CLI connection. Original tool inputs stay in memory; only sanitized stdout is journaled. */
class ClaudeControlSession(private val requestId: RequestId, private val userMessage: JsonObject) : Closeable {
    private val initializeId = "initialize-${UUID.randomUUID()}"
    private val queue = Channel<ByteArray>(18)
    val input = queue.receiveAsFlow()
    private var initialized = false
    private var finished = false
    private val pending = mutableMapOf<String, JsonObject>()
    private val seen = mutableSetOf<String>()
    init {
        enqueue(buildJsonObject {
            put("type", "control_request"); put("request_id", initializeId)
            putJsonObject("request") { put("subtype", "initialize"); put("hooks", JsonNull) }
        })
    }
    /** False consumes a transport-only event; true forwards the line to the runtime decoder.
     *  autoAllow answers a well-formed can_use_tool with the original input. The Android app
     *  sandbox is the execution boundary, so phone actions must not wait on a confirmation card. */
    @Synchronized fun onStdout(line: String, autoAllow: Boolean = false): Boolean {
        check(!finished) { "CLI output after terminal result" }
        val value = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return true // Decoder rejects malformed/truncated approvals; no parameters can be authorized here.
        when (value["type"]?.jsonPrimitive?.contentOrNull) {
            "control_response" -> {
                val response = value["response"]?.jsonObject ?: error("Missing CLI initialization response")
                check(!initialized && response["request_id"]?.jsonPrimitive?.content == initializeId &&
                    response["subtype"]?.jsonPrimitive?.content == "success") { "CLI initialization failed" }
                initialized = true
                enqueue(userMessage)
                return false
            }
            "control_request" -> {
                check(initialized) { "CLI requested permission before initialization" }
                val fact = ProtocolDecoder(AgentId.CLAUDE_CODE).decode(line).filterIsInstance<AgentFact.Approval>().singleOrNull()
                    ?: error("Unsupported CLI control request")
                val request = value.getValue("request").jsonObject
                pending[fact.id]?.let { check(it == request) { "Conflicting CLI permission request" }; return false }
                check(fact.id !in seen && seen.size < 512 && pending.size < 16) { "Reused or excessive CLI permission requests" }
                pending[fact.id] = request
                seen += fact.id
                if (autoAllow && offer(requestId, fact.id, ApprovalChoice.ALLOW_ONCE)) return false
            }
            "result" -> {
                check(initialized && pending.isEmpty()) { "CLI completed with unresolved permissions" }
                finished = true
                queue.close() // EOF only after a terminal CLI result, never while waiting for a decision.
            }
        }
        return true
    }
    /** Called only after the matching decision has been persisted by the coordinator. */
    @Synchronized fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean {
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
}
