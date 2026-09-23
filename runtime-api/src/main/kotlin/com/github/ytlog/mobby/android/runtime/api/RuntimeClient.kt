package com.github.ytlog.mobby.android.runtime.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

@Serializable

@JvmInline value class RequestId(val value: String)
@Serializable
@JvmInline value class RunId(val value: String)
@Serializable
@JvmInline value class CommandId(val value: String)
@Serializable
@JvmInline value class WorkspaceRef(val value: String)
@Serializable
@JvmInline value class ResourceRef(val value: String)
@Serializable
@JvmInline value class SessionRef(val value: String)
@Serializable
@JvmInline value class CapabilityRef(val value: String)
/** Includes a configuration version; rotating credentials does not modify admitted runs. */
@Serializable
data class GatewayProfileRef(val id: String, val version: Long)
@Serializable
enum class AgentId { CODEX, CLAUDE_CODE, OPEN_CODE }
@Serializable
enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
@Serializable
enum class ErrorCode {
    INPUT_TOO_LARGE,
    NOT_READY, BUSY, UNSUPPORTED_CAPABILITY, INVALID_CONFIG, PERMISSION_DENIED,
    PROTOCOL_ERROR, DISCONNECTED, RESOURCE_MISSING, STORAGE_FULL, RESOURCE_BUDGET_EXCEEDED, TIMEOUT,
    INTERRUPTED, INCOMPATIBLE_VERSION, REQUEST_CONFLICT, STALE_APPROVAL, NOT_FOUND
}
@Serializable
data class RuntimeError(val code: ErrorCode, val retryable: Boolean = false, val diagnosticRef: ResourceRef? = null)
@Serializable
sealed interface InputPart {
    @Serializable
    data class Text(val text: String) : InputPart
    @Serializable
    data class Resource(val ref: ResourceRef) : InputPart
}
@Serializable
enum class RequestedOutput { TEXT, SKILL_PROPOSAL }
@Serializable
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
    val apiMajor: Int = 1,
    val requestedOutput: RequestedOutput = RequestedOutput.TEXT
)
@Serializable
data class RunConfigSnapshot(
    val agentId: AgentId, val workspaceRef: WorkspaceRef, val modelId: String,
    val reasoningLevel: String?, val gatewayProfileRef: GatewayProfileRef,
    val capabilityRefs: Set<CapabilityRef>, val requestedOutput: RequestedOutput = RequestedOutput.TEXT
)
@Serializable
sealed interface SubmitResult {
    @Serializable
    data class Accepted(val runId: RunId, val acceptedConfig: RunConfigSnapshot) : SubmitResult
    @Serializable
    data class Rejected(val error: RuntimeError, val activeRunId: RunId? = null) : SubmitResult
}
@Serializable
sealed interface RequestLookup {
    @Serializable
    data class Found(val runId: RunId) : RequestLookup
    @Serializable
    data object NotFound : RequestLookup
    @Serializable
    data object Unavailable : RequestLookup
}
@Serializable
enum class CancelReason { USER_REQUEST }
@Serializable
data class CancelRequest(val commandId: CommandId, val runId: RunId, val reason: CancelReason = CancelReason.USER_REQUEST)
@Serializable
enum class ApprovalChoice { ALLOW_ONCE, DENY }
@Serializable
data class ApprovalDecision(
    val commandId: CommandId, val runId: RunId, val approvalId: String,
    val choice: ApprovalChoice, val expectedRevision: Long
)
@Serializable
sealed interface CommandResult {
    @Serializable
    data object Accepted : CommandResult
    @Serializable
    data object AlreadyTerminal : CommandResult
    @Serializable
    data class Rejected(val error: RuntimeError) : CommandResult
}
@Serializable
data class ModelCapability(val id: String, val reasoningLevels: Set<String>)
@Serializable
data class AgentCapability(
    val agentId: AgentId, val models: List<ModelCapability>, val unavailableReason: RuntimeError? = null,
    val supportsResume: Boolean = false, val supportsApproval: Boolean = false,
    val skillCapabilities: Set<CapabilityRef> = emptySet(),
    val maxInputBytes: Int = 65536, val supportsResources: Boolean = false, val supportsImages: Boolean = false
)
@Serializable
data class RuntimeCapabilities(val engineVersion: String, val agents: List<AgentCapability>, val apiMajor: Int = 1, val apiMinor: Int = 0)
@Serializable
sealed interface CapabilityResult {
    @Serializable
    data class Available(val capabilities: RuntimeCapabilities) : CapabilityResult
    @Serializable
    data class Unavailable(val error: RuntimeError) : CapabilityResult
}
@Serializable
enum class RunPhase(val terminal: Boolean = false) {
    ACCEPTED, STARTING, RUNNING, AWAITING_APPROVAL, CANCELLING,
    SUCCEEDED(true), FAILED(true), CANCELLED(true), TIMED_OUT(true), INTERRUPTED(true), OUTCOME_UNKNOWN(true)
}
/** A successful protocol message alone is insufficient. The turn is confirmed by a zero exit, or by an explicit confirmation while the process stays open for the next turn. */
@Serializable
data class TerminalEvidence(val protocolSucceeded: Boolean?, val exitCode: Int?, val error: RuntimeError? = null, val terminationConfirmed: Boolean = exitCode != null)
@Serializable
data class PendingApproval(val approvalId: String, val revision: Long, val actionSummary: String, val scopeSummary: String)
@Serializable
data class OutputSegment(val messageId: String, val chunkIndex: Long, val ref: ResourceRef)
/**
 * One conversation step. Upper layers switch on this type.
 * The step's output segments are the body text for that type:
 * thinking text, command result, file content, unified diff, or action result.
 * A missing body on previously stored snapshots reads as [Action] with name `tool`.
 */
@Serializable
sealed interface StepBody {
    @Serializable
    @SerialName("thinking")
    data object Thinking : StepBody
    /** `command` is the shell text after a `bash -lc` / `sh -lc` wrapper is removed. */
    @Serializable
    @SerialName("command")
    data class Command(val command: String) : StepBody
    @Serializable
    @SerialName("file_read")
    data class FileRead(val path: String) : StepBody
    @Serializable
    @SerialName("file_write")
    data class FileWrite(val path: String) : StepBody
    /** One tool call may change several files. Output segments are the unified diff. */
    @Serializable
    @SerialName("file_diff")
    data class FileDiff(val paths: List<String>) : StepBody
    /** Any other tool. `detail` is plain text, already taken out of the CLI payload. */
    @Serializable
    @SerialName("action")
    data class Action(val name: String, val detail: String = "") : StepBody
}

/** A later tool event replaces the step only when the new body fills in a blank or longer identity field. */
fun StepBody.supersedes(previous: StepBody): Boolean = when {
    this == previous -> false
    previous is StepBody.Action && previous.detail.isBlank() && this !is StepBody.Action -> true
    this is StepBody.Command && previous is StepBody.Command -> command.length > previous.command.length
    this is StepBody.FileRead && previous is StepBody.FileRead -> path.length > previous.path.length
    this is StepBody.FileWrite && previous is StepBody.FileWrite -> path.length > previous.path.length
    this is StepBody.FileDiff && previous is StepBody.FileDiff ->
        paths.isNotEmpty() && paths != previous.paths && (previous.paths.isEmpty() || paths.joinToString().length > previous.paths.joinToString().length)
    this is StepBody.Action && previous is StepBody.Action ->
        detail.length > previous.detail.length || (name.isNotBlank() && previous.name == "tool" && name != "tool")
    else -> false
}
@Serializable
data class ToolSnapshot(val stepId: String, val body: StepBody = StepBody.Action("tool", ""),
    val outcome: ToolOutcome? = null, val output: List<OutputSegment> = emptyList(), val order: Long = -1)
@Serializable
data class RunSnapshot(
    val runId: RunId, val phase: RunPhase, val revision: Long, val lastSequence: Long,
    val acceptedConfig: RunConfigSnapshot, val sessionRef: SessionRef? = null,
    val pendingApprovals: List<PendingApproval> = emptyList(), val terminalEvidence: TerminalEvidence? = null,
    val artifacts: List<ResourceRef> = emptyList(), val outputSegments: List<OutputSegment> = emptyList(),
    val steps: List<ToolSnapshot> = emptyList(), val progressSummary: String? = null
)
@Serializable
sealed interface SnapshotResult {
    @Serializable
    data class Found(val snapshot: RunSnapshot) : SnapshotResult
    @Serializable
    data class Unavailable(val error: RuntimeError) : SnapshotResult
}
@Serializable
data class EventCursor(val runId: RunId, val sequence: Long)
@Serializable
data class EventEnvelope(
    val eventId: String, val runId: RunId, val sequence: Long, val occurredAtEpochMillis: Long,
    val payload: RuntimeEvent, val apiMajor: Int = 1, val apiMinor: Int = 0
)
@Serializable
enum class ToolOutcome { SUCCEEDED, FAILED, CANCELLED }
@Serializable
sealed interface RuntimeEvent {
    @Serializable
    data class RunAccepted(val config: RunConfigSnapshot) : RuntimeEvent
    @Serializable
    data class RunStarted(val sessionRef: SessionRef?) : RuntimeEvent
    @Serializable
    data class ProgressSummary(val text: String) : RuntimeEvent
    @Serializable
    data class ToolStarted(val stepId: String, val body: StepBody, val order: Long = -1) : RuntimeEvent
    @Serializable
    data class ToolOutput(val stepId: String, val segment: OutputSegment) : RuntimeEvent
    @Serializable
    data class ToolFinished(val stepId: String, val outcome: ToolOutcome) : RuntimeEvent
    @Serializable
    data class AssistantDelta(val segment: OutputSegment) : RuntimeEvent
    @Serializable
    data class AssistantCompleted(val messageId: String) : RuntimeEvent
    @Serializable
    data class ApprovalRequired(val approval: PendingApproval) : RuntimeEvent
    @Serializable
    data class ApprovalResolved(val approvalId: String, val choice: ApprovalChoice) : RuntimeEvent
    @Serializable
    data class ArtifactAvailable(val ref: ResourceRef) : RuntimeEvent
    @Serializable
    data object CancellationRequested : RuntimeEvent
    @Serializable
    data class RunFinished(val phase: RunPhase, val evidence: TerminalEvidence) : RuntimeEvent
    /** Retain sanitized diagnostics for unknown events; never infer success from them. */
    @Serializable
    data class Unknown(val kind: String, val diagnosticRef: ResourceRef?) : RuntimeEvent
}
@Serializable
enum class ResyncReason { CURSOR_EXPIRED, INVALID_CURSOR, RUN_NOT_FOUND }
@Serializable
sealed interface RuntimeUpdate {
    /** Snapshot/cursor are read atomically. For resume, replay only events after this cursor.
     * A consumer must replace its projection with this baseline before applying subsequent events.
     * Implementations may use a historical baseline at `after`, or a newer complete snapshot.
     */
    @Serializable
    data class Baseline(val snapshot: RunSnapshot, val cursor: EventCursor) : RuntimeUpdate
    @Serializable
    data class Event(val envelope: EventEnvelope) : RuntimeUpdate
    @Serializable
    data class ResyncRequired(val reason: ResyncReason) : RuntimeUpdate
}
@Serializable
data class ArtifactReadRequest(val artifactRef: ResourceRef, val offset: Long, val limit: Int) {
    init { require(offset >= 0); require(limit in 1..65536) }
}
@Serializable
sealed interface ArtifactReadResult {
    /** Deliberately removed by retention; discard any earlier partial bytes for this reference. */
    @Serializable
    data object Expired : ArtifactReadResult
    /** Offsets count bytes, not decoded characters. Authorization is checked on every read. */
    @Serializable
    data class Chunk(val bytes: List<Byte>, val nextOffset: Long?, val truncated: Boolean) : ArtifactReadResult
    @Serializable
    data class Unavailable(val error: RuntimeError) : ArtifactReadResult
}
