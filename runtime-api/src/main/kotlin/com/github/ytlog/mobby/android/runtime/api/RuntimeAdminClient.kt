package com.github.ytlog.mobby.android.runtime.api

import kotlinx.coroutines.flow.StateFlow
import com.github.ytlog.mobby.android.runtime.api.gateway.*

enum class EnvironmentPhase { INITIALIZING, READY, FAILED }
data class EnvironmentSnapshot(val phase: EnvironmentPhase, val summary: String, val error: RuntimeError? = null)
sealed interface AdminResult<out T> {
    data class Success<T>(val value: T) : AdminResult<T>
    data class Failed(val error: RuntimeError) : AdminResult<Nothing>
}
data class EventHistorySettings(val retentionDays: Int = 30, val budgetMiB: Int = 32, val outputRetentionDays: Int = 30, val outputBudgetMiB: Int = 256, val attachmentBudgetMiB: Int = 512) {
    init { require(retentionDays in 1..3650 && budgetMiB in 1..1024 && outputRetentionDays in 1..3650 && outputBudgetMiB in 1..4096 && attachmentBudgetMiB in 1..8192) }
}
data class WorkspaceSummary(val ref: WorkspaceRef, val name: String)
interface RuntimeAdminClient {
    suspend fun listWorkspaces(): AdminResult<List<WorkspaceSummary>> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun createWorkspace(name: String): AdminResult<WorkspaceSummary> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun eventHistorySettings(): AdminResult<EventHistorySettings> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun saveEventHistorySettings(settings: EventHistorySettings): AdminResult<Unit> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun validateGateway(profile: GatewayProfileRef, agent: AgentId): AdminResult<GatewayCheck>
    suspend fun previewResource(ref: ResourceRef, workspace: WorkspaceRef, expanded: Boolean): AdminResult<ResourcePreview>
    suspend fun importResource(request: ImportResourceRequest): AdminResult<ResourceSummary>
    suspend fun resource(ref: ResourceRef, workspace: WorkspaceRef): AdminResult<ResourceSummary>
    val environment: StateFlow<EnvironmentSnapshot>
    suspend fun listSkills(agent: AgentId): AdminResult<List<SkillSummary>>
    suspend fun listPlugins(): AdminResult<List<PluginSummary>> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun readSkill(ref: CapabilityRef): AdminResult<SkillPreview>
    suspend fun previewManualSkill(request: ManualSkillRequest): AdminResult<SkillPreview>
    suspend fun previewSkill(markdown: String): AdminResult<SkillPreview>
    suspend fun importSkill(agent: AgentId, markdown: String): AdminResult<SkillSummary>
    suspend fun saveManualSkill(request: ManualSkillRequest): AdminResult<SkillSummary>
    suspend fun initialize(): AdminResult<Unit>
    suspend fun listGatewayProfiles(): AdminResult<List<GatewayProfileSummary>>
    suspend fun inspectGateway(request: InspectGatewayRequest): AdminResult<GatewayInspectionSummary>
    suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary>
    suspend fun defaultGateway(): AdminResult<GatewaySelection?> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun selectDefaultGateway(selection: GatewaySelection): AdminResult<Unit> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
    suspend fun deleteGatewayProfile(id: String): AdminResult<Unit> = AdminResult.Failed(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
}

/** Internal diagnostics, deliberately separate from the product Agent enum and conversation API. */
data class DiagnosticState(val phase: RunPhase? = null, val output: List<String> = emptyList(), val error: RuntimeError? = null)
interface RuntimeDiagnosticsClient {
    val state: StateFlow<DiagnosticState>
    suspend fun executeShell(command: String): CommandResult
    suspend fun stopShell(): CommandResult
}

/** Import and preview never execute skills. Saved files participate in native CLI discovery. */
enum class SkillSource { USER, BUILTIN }
enum class SkillIssue { INVALID_FRONTMATTER, UNCLOSED_FRONTMATTER, INVALID_NAME, INVALID_DESCRIPTION, EMPTY_BODY }
data class SkillSummary(val ref: CapabilityRef, val agent: AgentId, val name: String, val description: String,
    val source: SkillSource, val available: Boolean, val error: RuntimeError? = null)
data class SkillPreview(val name: String, val description: String, val body: String, val markdown: String,
    val issues: List<SkillIssue>)
data class ManualSkillRequest(val agent: AgentId, val name: String, val description: String, val body: String)
enum class PluginAccessKind { NONE, RUNTIME, ACCESSIBILITY, DOCUMENT_TREE }
data class PluginGrantSummary(
    val ref: CapabilityRef, val label: String, val available: Boolean,
    val permissions: List<String> = emptyList(), val reason: String? = null,
)
data class PluginSummary(
    val ref: CapabilityRef, val name: String, val description: String, val category: String,
    val available: Boolean, val access: PluginAccessKind, val permissions: List<String> = emptyList(),
    val grant: PluginGrantSummary? = null, val reason: String? = null,
)

/** Transient bytes only; persisted requests contain the returned controlled reference. */
class ImportResourceRequest(val workspaceRef: WorkspaceRef, val name: String, val bytes: ByteArray) {
    override fun toString() = "ImportResourceRequest(bytes=${bytes.size})"
}
data class ResourceSummary(val ref: ResourceRef, val name: String, val sizeBytes: Int, val mediaType: String)

/** Transient, bounded PNG preview; never persisted in conversation state. */
class ResourcePreview(val bytes: ByteArray)
