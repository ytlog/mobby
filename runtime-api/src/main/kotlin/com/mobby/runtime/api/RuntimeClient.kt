package com.mobby.runtime.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** In-process v1 contract. Implementations own execution independently of callers/observers.
 * Accepted is durable admission, never evidence of process startup or success.
 * Only explicit cancel requests stop execution. DTOs contain references, never credentials.
 */
interface RuntimeClient {
    val connection: StateFlow<ConnectionState>
    suspend fun capabilities(): CapabilityResult
    /** Persist request and reserve the global slot atomically before replying. Same ID + same
     * request returns the original run (including after completion); changed payload is a conflict.
     * Busy never queues work. Reject incompatible major versions and unsupported input/capabilities.
     */
    suspend fun submit(request: RunRequest): SubmitResult
    suspend fun findByRequest(requestId: RequestId): RequestLookup
    /** Idempotent by commandId + payload. Accepted means CANCELLING, not CANCELLED.
     * A completed run returns AlreadyTerminal; a previously accepted cancellation beats late success.
     */
    suspend fun cancel(request: CancelRequest): CommandResult
    /** Match run, approval ID and revision. Closing UI never implicitly resolves an approval. */
    suspend fun resolveApproval(request: ApprovalDecision): CommandResult
    suspend fun snapshot(runId: RunId): SnapshotResult
    fun observe(runId: RunId, after: EventCursor? = null): Flow<RuntimeUpdate>
    suspend fun readArtifact(request: ArtifactReadRequest): ArtifactReadResult
}

@JvmInline value class RequestId(val value: String)
@JvmInline value class RunId(val value: String)
@JvmInline value class CommandId(val value: String)
@JvmInline value class WorkspaceRef(val value: String)
@JvmInline value class ResourceRef(val value: String)
@JvmInline value class SessionRef(val value: String)
@JvmInline value class CapabilityRef(val value: String)
/** Includes a configuration version; rotating credentials does not modify admitted runs. */
data class GatewayProfileRef(val id: String, val version: Long)
enum class AgentId { CODEX, CLAUDE_CODE }
enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
enum class ErrorCode {
    NOT_READY, BUSY, UNSUPPORTED_CAPABILITY, INVALID_CONFIG, PERMISSION_DENIED,
    PROTOCOL_ERROR, DISCONNECTED, RESOURCE_MISSING, STORAGE_FULL, TIMEOUT,
    INTERRUPTED, INCOMPATIBLE_VERSION, REQUEST_CONFLICT, STALE_APPROVAL, NOT_FOUND
}
data class RuntimeError(val code: ErrorCode, val retryable: Boolean = false, val diagnosticRef: ResourceRef? = null)
sealed interface InputPart {
    data class Text(val text: String) : InputPart
    data class Resource(val ref: ResourceRef) : InputPart
}
data class RunRequest(
    val requestId: RequestId,
    val agentId: AgentId,
    val workspaceRef: WorkspaceRef,
    val inputParts: List<InputPart>,
    val modelId: String,
    val gatewayProfileRef: GatewayProfileRef,
    val reasoningLevel: String? = null,
    val sessionRef: SessionRef? = null,
    val capabilityRefs: Set<CapabilityRef> = emptySet(),
    val apiMajor: Int = 1
)
data class RunConfigSnapshot(
    val agentId: AgentId, val workspaceRef: WorkspaceRef, val modelId: String,
    val reasoningLevel: String?, val gatewayProfileRef: GatewayProfileRef,
    val capabilityRefs: Set<CapabilityRef>
)
sealed interface SubmitResult {
    data class Accepted(val runId: RunId, val acceptedConfig: RunConfigSnapshot) : SubmitResult
    data class Rejected(val error: RuntimeError, val activeRunId: RunId? = null) : SubmitResult
}
sealed interface RequestLookup {
    data class Found(val runId: RunId) : RequestLookup
    data object NotFound : RequestLookup
    data object Unavailable : RequestLookup
}
enum class CancelReason { USER_REQUEST }
data class CancelRequest(val commandId: CommandId, val runId: RunId, val reason: CancelReason = CancelReason.USER_REQUEST)
enum class ApprovalChoice { ALLOW_ONCE, DENY }
data class ApprovalDecision(
    val commandId: CommandId, val runId: RunId, val approvalId: String,
    val choice: ApprovalChoice, val expectedRevision: Long
)
sealed interface CommandResult {
    data object Accepted : CommandResult
    data object AlreadyTerminal : CommandResult
    data class Rejected(val error: RuntimeError) : CommandResult
}
data class ModelCapability(val id: String, val reasoningLevels: Set<String>)
data class AgentCapability(
    val agentId: AgentId, val models: List<ModelCapability>, val unavailableReason: RuntimeError? = null,
    val supportsResume: Boolean = false, val supportsApproval: Boolean = false,
    val skillCapabilities: Set<CapabilityRef> = emptySet(),
    val maxInputBytes: Int = 65536, val supportsResources: Boolean = false
)
data class RuntimeCapabilities(val engineVersion: String, val agents: List<AgentCapability>, val apiMajor: Int = 1, val apiMinor: Int = 0)
sealed interface CapabilityResult {
    data class Available(val capabilities: RuntimeCapabilities) : CapabilityResult
    data class Unavailable(val error: RuntimeError) : CapabilityResult
}
enum class RunPhase(val terminal: Boolean = false) {
    ACCEPTED, STARTING, RUNNING, AWAITING_APPROVAL, CANCELLING,
    SUCCEEDED(true), FAILED(true), CANCELLED(true), TIMED_OUT(true), INTERRUPTED(true), OUTCOME_UNKNOWN(true)
}
/** A successful protocol message alone is insufficient: process exit must also be confirmed. */
data class TerminalEvidence(val protocolSucceeded: Boolean?, val exitCode: Int?, val error: RuntimeError? = null)
data class PendingApproval(val approvalId: String, val revision: Long, val actionSummary: String, val scopeSummary: String)
data class OutputSegment(val messageId: String, val chunkIndex: Long, val ref: ResourceRef)
data class RunSnapshot(
    val runId: RunId, val phase: RunPhase, val revision: Long, val lastSequence: Long,
    val acceptedConfig: RunConfigSnapshot, val sessionRef: SessionRef? = null,
    val pendingApprovals: List<PendingApproval> = emptyList(), val terminalEvidence: TerminalEvidence? = null,
    val artifacts: List<ResourceRef> = emptyList(), val outputSegments: List<OutputSegment> = emptyList()
)
sealed interface SnapshotResult {
    data class Found(val snapshot: RunSnapshot) : SnapshotResult
    data class Unavailable(val error: RuntimeError) : SnapshotResult
}
data class EventCursor(val runId: RunId, val sequence: Long)
data class EventEnvelope(
    val eventId: String, val runId: RunId, val sequence: Long, val occurredAtEpochMillis: Long,
    val payload: RuntimeEvent, val apiMajor: Int = 1, val apiMinor: Int = 0
)
enum class ToolOutcome { SUCCEEDED, FAILED, CANCELLED }
sealed interface RuntimeEvent {
    data class RunAccepted(val config: RunConfigSnapshot) : RuntimeEvent
    data class RunStarted(val sessionRef: SessionRef?) : RuntimeEvent
    data class ProgressSummary(val text: String) : RuntimeEvent
    data class ToolStarted(val stepId: String, val toolKind: String, val summary: String) : RuntimeEvent
    data class ToolOutput(val stepId: String, val segment: OutputSegment) : RuntimeEvent
    data class ToolFinished(val stepId: String, val outcome: ToolOutcome) : RuntimeEvent
    data class AssistantDelta(val segment: OutputSegment) : RuntimeEvent
    data class AssistantCompleted(val messageId: String) : RuntimeEvent
    data class ApprovalRequired(val approval: PendingApproval) : RuntimeEvent
    data class ApprovalResolved(val approvalId: String, val choice: ApprovalChoice) : RuntimeEvent
    data class ArtifactAvailable(val ref: ResourceRef) : RuntimeEvent
    data object CancellationRequested : RuntimeEvent
    data class RunFinished(val phase: RunPhase, val evidence: TerminalEvidence) : RuntimeEvent
    /** Retain sanitized diagnostics for unknown events; never infer success from them. */
    data class Unknown(val kind: String, val diagnosticRef: ResourceRef?) : RuntimeEvent
}
enum class ResyncReason { CURSOR_EXPIRED, INVALID_CURSOR, RUN_NOT_FOUND }
sealed interface RuntimeUpdate {
    /** Snapshot/cursor are read atomically. For resume, replay only events after this cursor.
     * A consumer must replace its projection with this baseline before applying subsequent events.
     * Implementations may use a historical baseline at `after`, or a newer complete snapshot.
     */
    data class Baseline(val snapshot: RunSnapshot, val cursor: EventCursor) : RuntimeUpdate
    data class Event(val envelope: EventEnvelope) : RuntimeUpdate
    data class ResyncRequired(val reason: ResyncReason) : RuntimeUpdate
}
data class ArtifactReadRequest(val artifactRef: ResourceRef, val offset: Long, val limit: Int) {
    init { require(offset >= 0); require(limit in 1..65536) }
}
sealed interface ArtifactReadResult {
    /** Offsets count bytes, not decoded characters. Authorization is checked on every read. */
    data class Chunk(val bytes: List<Byte>, val nextOffset: Long?, val truncated: Boolean) : ArtifactReadResult
    data class Unavailable(val error: RuntimeError) : ArtifactReadResult
}
