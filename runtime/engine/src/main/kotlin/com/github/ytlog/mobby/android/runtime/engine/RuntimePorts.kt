package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.flow.StateFlow
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperationPort

enum class StopCause { USER, TIMEOUT, HOST_STOP, STORAGE_FAILURE, PROTOCOL_FAILURE }
enum class InsertionOffer { ACCEPTED, NOT_READY, UNSUPPORTED }
data class ProcessResult(val exitCode: Int?, val terminationConfirmed: Boolean, val error: ErrorCode? = null, val retained: Boolean = false)
/** A live CLI can take another turn only when the process, engine, workspace, model, gateway, skills and output mode are unchanged. */
data class LiveSessionBinding(
    val agentId: AgentId, val workspaceRef: WorkspaceRef, val modelId: String, val gatewayProfileRef: GatewayProfileRef,
    val capabilityRefs: Set<CapabilityRef>, val requestedOutput: RequestedOutput, val sessionId: String,
) {
    private fun sameExecution(request: RunRequest) = agentId == request.agentId && workspaceRef == request.workspaceRef && modelId == request.modelId &&
        gatewayProfileRef == request.gatewayProfileRef && capabilityRefs == request.capabilityRefs &&
        requestedOutput == request.requestedOutput
    fun accepts(request: RunRequest) = sameExecution(request) && sessionId == request.sessionRef?.value
    fun acceptsNewSession(request: RunRequest) = sameExecution(request) && request.sessionRef == null
}
interface ProcessPort {
    /** True only if a live adapter accepts this text for the named request. */
    fun offerInsertion(requestId: RequestId, text: String): InsertionOffer = InsertionOffer.UNSUPPORTED
    fun offerDeviceResponse(request: com.github.ytlog.mobby.android.runtime.api.device.DeviceOperationResponse): Boolean = false
    /** Returns when the turn ends. A retained process stays open for a compatible follow-up; stop is explicit. */
    suspend fun execute(request: RunRequest, stop: StateFlow<StopCause?>, devices: DeviceOperationPort, output: suspend (String, Boolean) -> Unit): ProcessResult
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
