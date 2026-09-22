package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.io.Closeable

/** One turn, independent of which CLI is underneath. */
data class AgentTurn(
    val requestId: RequestId,
    val prompt: String,
    val images: List<TurnImage> = emptyList(),
    val outputSchema: JsonElement? = null,
)

data class TurnImage(val mediaType: String, val path: String, val base64: String)

/** The user message both the control session and tests send to Claude. */
fun AgentTurn.claudeWireMessage(): JsonObject = buildJsonObject {
    put("type", "user")
    putJsonObject("message") {
        put("role", "user")
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", prompt) })
            images.forEach { image ->
                add(buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64"); put("media_type", image.mediaType); put("data", image.base64)
                    }
                })
            }
        }
    }
}

/**
 * Operation API for a live CLI process.
 * Submit a turn, answer an approval, then either submit the next turn or release the process.
 * [onStdout] returns only the shared event lines; Claude and Codex wire messages stay inside the implementation.
 */
interface AgentSession : Closeable {
    val input: Flow<ByteArray>
    fun sessionId(): String?
    fun onStdout(line: String, autoAllow: Boolean = false): List<String>
    fun submit(turn: AgentTurn)
    fun offer(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean
    fun takeTurnEnded(): Boolean
    fun release()
}

data class AgentConnection(val session: AgentSession, val arguments: List<String>)

object AgentSessions {
    /** Cold start. A process that is still alive is continued with [AgentSession.submit], not opened again. */
    fun connect(request: RunRequest, executable: String, cwd: String, turn: AgentTurn): AgentConnection {
        require(cwd.startsWith("/") && '\u0000' !in cwd)
        val session: AgentSession = when (request.agentId) {
            AgentId.CLAUDE_CODE -> ClaudeControlSession()
            AgentId.CODEX -> CodexAppServerSession(cwd, request.modelId, request.sessionRef?.value)
        }
        session.submit(turn)
        val arguments = AgentCommand.arguments(
            request, executable, turn.prompt,
            streamInput = request.agentId == AgentId.CLAUDE_CODE,
            approvals = request.agentId == AgentId.CLAUDE_CODE,
        )
        return AgentConnection(session, arguments)
    }
}
