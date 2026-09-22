package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.flow.StateFlow

enum class StopCause { USER, TIMEOUT, HOST_STOP, STORAGE_FAILURE, PROTOCOL_FAILURE }
data class ProcessResult(val exitCode: Int?, val terminationConfirmed: Boolean, val error: ErrorCode? = null)
interface ProcessPort {
    /** Returns only after stream drain and process cleanup. Stop is explicit, not caller subscription. */
    suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, output: suspend (String, Boolean) -> Unit): ProcessResult
    /** Nonblocking handoff after durable acceptance. Never applies to another request or unknown approval.
     * True means queued, not executed. Delivery/write failure must fail execute; never replay after restart. */
    fun offerApproval(requestId: RequestId, approvalId: String, choice: ApprovalChoice): Boolean = false
}
interface EnvironmentPort {
    suspend fun capabilities(): CapabilityResult
    suspend fun validate(request: RunRequest): RuntimeError?
}
@kotlinx.serialization.Serializable
data class CommandRecord(val id: CommandId, val fingerprint: String, val result: CommandResult)
data class RequestRecord(val digest: String, val runId: RunId)
interface JournalPort {
    suspend fun command(id: CommandId): CommandRecord?
    suspend fun recordCommand(command: CommandRecord)
    suspend fun releaseRecoveredSlot(runId: RunId)
    suspend fun find(requestId: RequestId): RequestRecord?
    /** Atomic insert: request index + first event + snapshot. Throws on persistence failure. */
    suspend fun accept(requestId: RequestId, digest: String, snapshot: RunSnapshot, event: EventEnvelope)
    /** Atomic compare-and-advance of snapshot + event; exactly one terminal event. */
    suspend fun append(snapshot: RunSnapshot, event: EventEnvelope, command: CommandRecord? = null)
    suspend fun snapshot(runId: RunId): RunSnapshot?
    suspend fun eventsAfter(runId: RunId, sequence: Long, limit: Int): List<EventEnvelope>
    suspend fun unfinished(): List<RunSnapshot>
}
interface OutputStorePort {
    /** Content must be sanitized by the platform before it reaches this store or the journal. */
    suspend fun write(runId: RunId, name: String, text: String): ResourceRef
    suspend fun read(request: ArtifactReadRequest): ArtifactReadResult
}
