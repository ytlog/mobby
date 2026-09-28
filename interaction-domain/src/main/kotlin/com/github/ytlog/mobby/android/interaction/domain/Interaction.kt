package com.github.ytlog.mobby.android.interaction.domain

import com.github.ytlog.mobby.android.deviceinteraction.model.*
import com.github.ytlog.mobby.android.localization.CatalogIds

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

data class Message(val id: String, val text: String, val order: Long = Long.MAX_VALUE)
/** Conversation step. Reply text stays on [Message]. Body text is already joined from output segments. */
sealed interface Step {
    val id: String
    val outcome: String?
    val order: Long
    data class Thinking(override val id: String, val text: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    data class Command(override val id: String, val command: String, val result: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    data class FileRead(override val id: String, val path: String, val content: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    data class FileWrite(override val id: String, val path: String, val content: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    data class FileDiff(override val id: String, val paths: List<String>, val diff: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    data class Action(override val id: String, val name: String, val detail: String, val result: String, override val outcome: String?, override val order: Long = Long.MAX_VALUE) : Step
    fun displayedText(): String = when (this) {
        is Thinking -> text
        is Command -> command + result
        is FileRead -> content
        is FileWrite -> content
        is FileDiff -> diff
        is Action -> detail + result
    }
}
data class SkillProposal(val ref: String, val markdown: String, val agent: AgentId)
data class Turn(
    val id: TurnId, val userText: String, val execution: ExecutionId?, val phase: ExecutionPhase?,
    val messages: List<Message> = emptyList(), val steps: List<Step> = emptyList(),
    val diagnostics: List<Message> = emptyList(), val failure: String? = null,
    val progress: ProgressNotice? = null, val pending: Boolean = false, val occupied: Boolean = false, val queued: Boolean = false,
    val expanded: Boolean? = null, val expandedSteps: Set<String> = emptySet(),
    val skillProposals: List<SkillProposal> = emptyList(), val creatingSkill: Boolean = false, val proposalsLoading: Boolean = false, val attachments: List<String> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList(), val deviceOperations: List<DeviceRecord> = emptyList(),
    val workspace: String = "default", val project: String? = null
) {
    /** Replies stay where they were produced. Adjacent tool calls form one run and collapse after they finish. */
    fun transcript(): List<TranscriptEntry> {
        val ordered = (messages.map { it.order to TranscriptPiece.Reply(it) } + steps.map { it.order to TranscriptPiece.Tool(it) } + deviceOperations.map { it.order to TranscriptPiece.Device(it) })
            .sortedWith(compareBy<Pair<Long, TranscriptPiece>>({ it.first }, { if (it.second is TranscriptPiece.Reply) 0 else 1 }))
        val result = mutableListOf<TranscriptEntry>()
        val run = mutableListOf<Step>()
        fun flush() {
            if (run.isNotEmpty()) {
                result += TranscriptEntry.ToolRun(run.toList())
                run.clear()
            }
        }
        for ((_, piece) in ordered) when (piece) {
            is TranscriptPiece.Reply -> { flush(); result += TranscriptEntry.Reply(piece.message) }
            is TranscriptPiece.Tool -> run += piece.step
            is TranscriptPiece.Device -> { flush(); result += TranscriptEntry.Device(piece.record) }
        }
        flush()
        return result
    }
}
private sealed interface TranscriptPiece {
    data class Device(val record: DeviceRecord) : TranscriptPiece
    data class Reply(val message: Message) : TranscriptPiece
    data class Tool(val step: Step) : TranscriptPiece
}
sealed interface TranscriptEntry {
    data class Device(val record: DeviceRecord) : TranscriptEntry
    data class Reply(val message: Message) : TranscriptEntry
    data class ToolRun(val steps: List<Step>) : TranscriptEntry
}
data class ConversationSummary(val conversation: Conversation, val phase: ExecutionPhase? = null, val occupied: Boolean = false, val execution: ExecutionId? = null, val deviceOperation: com.github.ytlog.mobby.android.deviceinteraction.model.DeviceOperation? = null)
data class ConversationDetail(val conversation: Conversation, val turns: List<Turn>, val hasEarlier: Boolean = false)
data class Project(val name: String, val workspace: String, val skills: Set<String> = emptySet(), val rules: String = "")
data class InteractionState(
    val conversations: List<ConversationSummary> = emptyList(), val selected: ConversationDetail? = null,
    val loading: Boolean = true, val error: String? = null, val projects: List<Project> = emptyList()
) {
    val occupied: ConversationSummary? get() = conversations.firstOrNull { it.occupied }
}
data class AgentOption(val agent: AgentId, val models: Map<String, Set<String>>, val unavailable: String?, val resume: Boolean, val skills: Set<String>, val resources: Boolean = false, val images: Boolean = false, val approvals: Boolean = false, val modelNames: Map<String, String> = emptyMap())
data class SystemStatus(val ready: Boolean = false, val connected: Boolean = false, val message: String = AppStrings.connecting, val diagnosticBusy: Boolean = false)
data class DiagnosticOutput(val phase: ExecutionPhase?, val lines: List<String>)
sealed interface OperationResult {
    data object Done : OperationResult
    data class Failed(val message: String) : OperationResult
}
data class Skill(val ref: String, val agent: AgentId, val name: String, val description: String, val source: String, val available: Boolean, val unavailableReason: String?)
data class SkillContent(val name: String, val description: String, val body: String, val markdown: String, val issues: List<String>)
sealed interface DataResult<out T> {
    data class Loaded<T>(val value: T) : DataResult<T>
    data class Failed(val message: String) : DataResult<Nothing>
}
data class Attachment(val ref: String, val name: String, val sizeBytes: Int, val mediaType: String = "text/plain")
class AttachmentPreview(val bytes: ByteArray)
data class EventHistoryLimits(val days: Int = 30, val mib: Int = 32, val outputDays: Int = 30, val outputMiB: Int = 256, val attachmentMiB: Int = 512, val resourceCacheMiB: Int = 128)
data class WorkspaceOption(val ref: String, val name: String)
enum class PluginAccess { NONE, RUNTIME, ACCESSIBILITY, DOCUMENT_TREE }
data class PluginGrant(val ref: String, val label: String, val available: Boolean, val unavailableReason: String?, val permissions: List<String> = emptyList())
data class Plugin(
    val ref: String, val name: String, val description: String, val available: Boolean, val unavailableReason: String?,
    val category: String = CatalogIds.PHONE, val access: PluginAccess = PluginAccess.NONE,
    val permissions: List<String> = emptyList(), val grant: PluginGrant? = null,
)
enum class AppFunctionAvailability { AVAILABLE, UNSUPPORTED_DEVICE, PERMISSION_DENIED, SYSTEM_DENIED, QUERY_FAILED }
data class AppFunctionParameter(val name: String, val description: String, val required: Boolean, val type: String)
data class PublishedAppFunction(val ref: String, val packageName: String, val appName: String, val functionId: String,
    val description: String, val enabled: Boolean, val unavailableReason: String?, val parameters: List<AppFunctionParameter>)
data class AppFunctionDirectory(val availability: AppFunctionAvailability, val functions: List<PublishedAppFunction> = emptyList())
interface SystemPort {
    suspend fun workspaces(): DataResult<List<WorkspaceOption>> = DataResult.Failed(AppStrings.thisRuntimeDoesNotSupportWorkspaceSelection)
    suspend fun createWorkspace(name: String): DataResult<WorkspaceOption> = DataResult.Failed(AppStrings.thisRuntimeDoesNotSupportCreatingWorkspaces)
    suspend fun eventHistoryLimits(): DataResult<EventHistoryLimits> = DataResult.Failed(AppStrings.thisRuntimeDoesNotSupportStorageSettings)
    suspend fun saveEventHistoryLimits(value: EventHistoryLimits): OperationResult = OperationResult.Failed(AppStrings.thisRuntimeDoesNotSupportStorageSettings)
    suspend fun beginCapture(conversation: String, workspace: String): DataResult<CameraCapture>
    suspend fun capture(): DataResult<CameraCapture?>
    suspend fun finishCapture(id: String, success: Boolean): DataResult<CameraCapture?>
    suspend fun discardCapture(id: String): OperationResult
    suspend fun previewCapture(id: String): DataResult<AttachmentPreview>
    suspend fun retainAttachmentGrants(locations: Set<String>)
    suspend fun importAttachment(workspace: String, location: String): DataResult<Attachment>
    suspend fun previewAttachment(workspace: String, ref: String, expanded: Boolean): DataResult<AttachmentPreview>
    suspend fun attachment(workspace: String, ref: String): DataResult<Attachment>
    suspend fun skills(agent: AgentId): DataResult<List<Skill>>
    suspend fun plugins(): DataResult<List<Plugin>> = DataResult.Loaded(emptyList())
    suspend fun appFunctions(): DataResult<AppFunctionDirectory> = DataResult.Loaded(AppFunctionDirectory(AppFunctionAvailability.UNSUPPORTED_DEVICE))
    suspend fun readSkill(ref: String): DataResult<SkillContent>
    suspend fun previewSkill(markdown: String): DataResult<SkillContent>
    suspend fun previewManualSkill(agent: AgentId, name: String, description: String, body: String): DataResult<SkillContent>
    suspend fun readSkillImport(location: String): DataResult<SkillContent>
    suspend fun importSkill(agent: AgentId, markdown: String): DataResult<Skill>
    suspend fun saveManualSkill(agent: AgentId, name: String, description: String, body: String): DataResult<Skill>
    val status: Flow<SystemStatus>
    val diagnostic: Flow<DiagnosticOutput>
    suspend fun agents(): List<AgentOption>
    suspend fun checkGateway(profile: GatewayProfile): DataResult<GatewayCheckReport>
    suspend fun gateways(): List<GatewayProfile>
    suspend fun saveGateway(edit: GatewayEdit): GatewaySaveResult
    suspend fun fetchGatewayModels(edit: GatewayEdit): DataResult<GatewayCatalogResult>
    suspend fun defaultGateway(): GatewayDefault?
    suspend fun selectDefaultGateway(profile: GatewayProfile): OperationResult
    suspend fun deleteGateway(id: String): OperationResult
    suspend fun initialize(): OperationResult
    suspend fun shell(command: String): OperationResult
    suspend fun stopShell(): OperationResult
}
interface InteractionRepository : ConversationRepository {
    /** Reconcile a stopped run directly with the runtime, independent of streamed event delivery. */
    suspend fun refreshExecution(id: ExecutionId)
    suspend fun cancelQueued(turnId: TurnId): OperationResult = OperationResult.Failed(AppStrings.unsupportedOperation)
    suspend fun saveProject(project: Project, createOnly: Boolean = false): OperationResult = OperationResult.Failed(AppStrings.thisStorageDoesNotSupportDefaultProjectWorkspaces)
    suspend fun createInProject(config: NextTurnConfig, project: String): ConversationId = error("Project creation is unsupported")
    suspend fun saveSkillProposal(proposal: SkillProposal, markdown: String): DataResult<Skill> = DataResult.Failed(AppStrings.thisConversationDoesNotSupportSavingGeneratedDrafts)
    suspend fun conversation(id: ConversationId): Conversation
    suspend fun awaitAttachmentRecovery()
    suspend fun loadEarlier(id: ConversationId)
    suspend fun revealTurn(id: ConversationId, turn: TurnId)
    suspend fun history(id: ConversationId): ConversationDetail
    suspend fun beginAttachment(id: ConversationId, pending: PendingAttachment)
    suspend fun finishAttachment(id: ConversationId, pendingId: String, result: DataResult<Attachment>)
    suspend fun discardAttachment(id: ConversationId, pendingId: String)
    suspend fun restoreDraft(id: ConversationId, text: String, attachments: List<String>)
    suspend fun setAttachment(id: ConversationId, ref: String, enabled: Boolean)
    val state: Flow<InteractionState>
    suspend fun setSkill(id: ConversationId, ref: String, enabled: Boolean)
    suspend fun createSkillConversation(id: ConversationId, creator: String): ConversationId
    suspend fun select(id: ConversationId)
    suspend fun create(config: NextTurnConfig): ConversationId
    suspend fun editDraft(id: ConversationId, text: String, selectionStart: Int, selectionEnd: Int): Draft
    suspend fun configure(id: ConversationId, config: NextTurnConfig): ConversationId
    suspend fun updateGateway(profile: GatewayProfile)
    suspend fun rename(id: ConversationId, title: String): OperationResult
    suspend fun pin(id: ConversationId)
    suspend fun setProject(id: ConversationId, project: String?): OperationResult
    suspend fun archive(id: ConversationId, archived: Boolean): OperationResult
    suspend fun delete(id: ConversationId, deleted: Boolean): OperationResult
    suspend fun expansion(turnId: TurnId, expanded: Boolean)
    suspend fun stepExpansion(turnId: TurnId, stepId: String, expanded: Boolean)
    suspend fun anchor(id: ConversationId, messageId: String?, offset: Int)
}

/** UI consumes these use cases, never Runtime DTOs, Service handles or a concrete database. */
class InteractionUseCases(
    private val repository: InteractionRepository,
    private val execution: ExecutionPort,
    private val system: SystemPort,
    private val nextId: () -> String,
    private val submissionScope: CoroutineScope,
    private val preferences: PreferencePort
) {
    suspend fun workspaces() = system.workspaces()
    suspend fun eventHistoryLimits() = system.eventHistoryLimits()
    suspend fun saveEventHistoryLimits(value: EventHistoryLimits) = submissionScope.async { system.saveEventHistoryLimits(value) }.await()
    val appearance get() = preferences.appearance
    suspend fun setAppearance(value: Appearance) = preferences.setAppearance(value)
    val state get() = repository.state
    val status get() = system.status
    val diagnostic get() = system.diagnostic
    suspend fun loadEarlier(id: ConversationId) = repository.loadEarlier(id)
    suspend fun revealTurn(id: ConversationId, turn: TurnId) = repository.revealTurn(id, turn)
    suspend fun history(id: ConversationId) = repository.history(id)
    suspend fun select(id: ConversationId) = repository.select(id)
    suspend fun create(config: NextTurnConfig, project: String? = null) =
        if (project == null) repository.create(config.copy(workspace = "default")) else repository.createInProject(config, project)
    suspend fun createProject(name: String, skills: Set<String> = emptySet()): OperationResult = submissionScope.async {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80 || trimmed.any { it.isISOControl() })
            return@async OperationResult.Failed(AppStrings.projectNameIsRequiredAndMustNotExceedCharacters)
        validateProjectSkills(skills)?.let { return@async it }
        val existing = (system.workspaces() as? DataResult.Loaded)?.value
            ?: return@async OperationResult.Failed(AppStrings.cannotReadWorkspacesRefreshAndRetry)
        existing.firstOrNull { it.name == trimmed }?.let { workspace ->
            return@async repository.saveProject(Project(trimmed, workspace.ref, skills), createOnly = true)
        }
        when (val created = system.createWorkspace(trimmed)) {
            is DataResult.Failed -> OperationResult.Failed(created.message)
            is DataResult.Loaded -> repository.saveProject(Project(trimmed, created.value.ref, skills), createOnly = true)
        }
    }.await()
    suspend fun saveProject(project: Project, createOnly: Boolean = false): OperationResult = submissionScope.async {
        validateProjectSkills(project.skills)?.let { return@async it }
        val workspaces = (system.workspaces() as? DataResult.Loaded)?.value
            ?: return@async OperationResult.Failed(AppStrings.cannotReadWorkspacesRefreshAndRetry)
        if (workspaces.none { it.ref == project.workspace }) return@async OperationResult.Failed(AppStrings.workspaceUnavailableSelectAnotherWorkspace)
        repository.saveProject(project, createOnly)
    }.await()
    private suspend fun validateProjectSkills(names: Set<String>): OperationResult.Failed? {
        if (names.size > 24) return OperationResult.Failed(AppStrings.skillChangedOrIsUnavailableSelectItAgain)
        if (names.isEmpty()) return null
        val available = (system.skills(AgentId.PI) as? DataResult.Loaded)?.value
            ?: return OperationResult.Failed(AppStrings.skillDirectoryUnavailablePleaseRetry)
        return if (available.filter { it.available }.map { it.name }.toSet().containsAll(names)) null
            else OperationResult.Failed(AppStrings.skillChangedOrIsUnavailableSelectItAgain)
    }
    suspend fun draft(id: ConversationId, text: String, start: Int, end: Int) = repository.editDraft(id, text, start, end)
    suspend fun configure(id: ConversationId, config: NextTurnConfig) = repository.configure(id, config)
    /** One Agent-independent entry point: queue a normal turn or insert into this conversation's live turn. */
    suspend fun prepareMessage(id: ConversationId, mode: MessageDeliveryMode): PreparedMessage = when (mode) {
        MessageDeliveryMode.QUEUE -> when (val result = repository.prepareTurn(id, TurnId(nextId()))) {
            is PrepareTurnResult.Prepared -> PreparedMessage.Turn(result.turn)
            is PrepareTurnResult.Queued -> PreparedMessage.Queued(result.turnId)
            is PrepareTurnResult.Rejected -> PreparedMessage.Rejected(result.reason)
        }
        MessageDeliveryMode.INSERT -> when (val result = repository.prepareInsertion(id, TurnId(nextId()))) {
            is PrepareInsertionResult.Prepared -> PreparedMessage.Insertion(result.insertion)
            is PrepareInsertionResult.Rejected -> PreparedMessage.Rejected(result.reason)
        }
    }
    suspend fun deliverMessage(message: PreparedMessage): Submission = when (message) {
        is PreparedMessage.Turn -> sendPrepared(message.turn)
        is PreparedMessage.Insertion -> insertPrepared(message.insertion)
        is PreparedMessage.Queued -> Submission.Queued(message.turnId)
        is PreparedMessage.Rejected -> Submission.Rejected(message.reason)
    }
    private suspend fun insertPrepared(insertion: PreparedInsertion): Submission = submissionScope.async {
        execution.insert(insertion).also { repository.recordInsertion(insertion, it) }
    }.await()
    suspend fun cancelQueued(turnId: TurnId) = repository.cancelQueued(turnId)
    private suspend fun sendPrepared(turn: TurnExecution): Submission = submissionScope.async {
        execution.submit(turn).also { repository.recordSubmission(turn, it) }
    }.await()
    suspend fun reconcile(id: ConversationId) = SubmitTurnUseCase(repository, execution).reconcile(id)
    suspend fun respondToDevice(request: DeviceInteractionResponse) = execution.respondToDevice(request)
    suspend fun stop(id: ExecutionId): StopResult {
        val result = StopRunUseCase(execution)(id)
        if (result == StopResult.Accepted || result == StopResult.AlreadyTerminal) repository.refreshExecution(id)
        return result
    }
    suspend fun resolvePermission(decision: PermissionDecision) = submissionScope.async { execution.resolvePermission(decision) }.await()
    suspend fun skills(agent: AgentId) = system.skills(agent)
    suspend fun plugins() = system.plugins()
    suspend fun appFunctions() = system.appFunctions()
    suspend fun readSkill(ref: String) = system.readSkill(ref)
    suspend fun previewSkill(markdown: String) = system.previewSkill(markdown)
    suspend fun previewManualSkill(agent: AgentId, name: String, description: String, body: String) = system.previewManualSkill(agent, name, description, body)
    suspend fun readSkillImport(location: String) = system.readSkillImport(location)
    suspend fun importSkill(agent: AgentId, markdown: String) = system.importSkill(agent, markdown)
    suspend fun saveSkillProposal(proposal: SkillProposal, markdown: String) = submissionScope.async { repository.saveSkillProposal(proposal, markdown) }.await()
    suspend fun saveManualSkill(agent: AgentId, name: String, description: String, body: String) = system.saveManualSkill(agent, name, description, body)
    suspend fun setSkill(id: ConversationId, skill: Skill, enabled: Boolean): OperationResult {
        if (enabled) {
            val current = (system.skills(skill.agent) as? DataResult.Loaded)?.value
                ?: return OperationResult.Failed(AppStrings.skillDirectoryUnavailablePleaseRetry)
            if (current.none { it.ref == skill.ref && it.available }) return OperationResult.Failed(AppStrings.skillChangedOrIsUnavailableSelectItAgain)
        }
        repository.setSkill(id, skill.ref, enabled)
        return OperationResult.Done
    }
    suspend fun setPlugin(id: ConversationId, plugin: Plugin, enabled: Boolean): OperationResult {
        if (enabled) {
            val current = (system.plugins() as? DataResult.Loaded)?.value
                ?: return OperationResult.Failed(AppStrings.pluginCatalogueUnavailablePleaseRetry)
            if (current.none { it.ref == plugin.ref && it.available })
                return OperationResult.Failed(plugin.unavailableReason ?: AppStrings.grantPermissionBeforeUse)
        }
        repository.setSkill(id, plugin.ref, enabled)
        return OperationResult.Done
    }
    suspend fun setAppFunction(id: ConversationId, function: PublishedAppFunction, enabled: Boolean): OperationResult {
        if (enabled) {
            val current = (system.appFunctions() as? DataResult.Loaded)?.value
                ?: return OperationResult.Failed(AppStrings.pluginCatalogueUnavailablePleaseRetry)
            if (current.availability != AppFunctionAvailability.AVAILABLE ||
                current.functions.none { it.ref == function.ref && it.enabled })
                return OperationResult.Failed(AppStrings.appFunctionNoLongerAvailable)
        }
        repository.setSkill(id, function.ref, enabled)
        return OperationResult.Done
    }
    suspend fun setPluginGrant(id: ConversationId, plugin: Plugin, enabled: Boolean): OperationResult {
        val grant = plugin.grant ?: return OperationResult.Failed(AppStrings.thisPluginHasNoSeparateWriteSwitch)
        if (enabled) {
            val current = (system.plugins() as? DataResult.Loaded)?.value
                ?: return OperationResult.Failed(AppStrings.pluginCatalogueUnavailablePleaseRetry)
            val live = current.firstOrNull { it.ref == plugin.ref } ?: return OperationResult.Failed(AppStrings.pluginCatalogueUnavailablePleaseRetry)
            val liveGrant = live.grant ?: return OperationResult.Failed(AppStrings.thisPluginHasNoSeparateWriteSwitch)
            if (!live.available) return OperationResult.Failed(live.unavailableReason ?: AppStrings.grantPermissionBeforeUse)
            if (!liveGrant.available) return OperationResult.Failed(liveGrant.unavailableReason ?: AppStrings.grantPermissionBeforeUse)
            if (plugin.ref !in repository.conversation(id).draft.capabilities) return OperationResult.Failed(AppStrings.enableFirst(plugin.name))
            repository.setSkill(id, liveGrant.ref, true)
        } else repository.setSkill(id, grant.ref, false)
        return OperationResult.Done
    }
    suspend fun removeSkill(id: ConversationId, ref: String) = repository.setSkill(id, ref, false)
    suspend fun createSkillConversation(id: ConversationId, agent: AgentId): DataResult<ConversationId> {
        val creator = (system.skills(agent) as? DataResult.Loaded)?.value?.firstOrNull { it.name == "skill-creator" && it.available }
            ?: return DataResult.Failed(AppStrings.thisAgentHasNoAvailableSkillCreatorReturnTo)
        return DataResult.Loaded(repository.createSkillConversation(id, creator.ref))
    }
    suspend fun beginCapture(conversation: ConversationId, workspace: String): DataResult<CameraCapture> {
        repository.awaitAttachmentRecovery()
        val current = repository.conversation(conversation)
        if (current.archived || current.deleted || current.config.workspace != workspace || current.draft.pendingAttachment != null || current.draft.attachments.size >= 4)
            return DataResult.Failed(AppStrings.cannotAddAPhotoToThisConversation)
        return system.beginCapture(conversation.value, workspace)
    }
    suspend fun capture(): DataResult<CameraCapture?> {
        repository.awaitAttachmentRecovery()
        return system.capture()
    }
    suspend fun finishCapture(id: String, success: Boolean) = system.finishCapture(id, success)
    suspend fun discardCapture(id: String) = system.discardCapture(id)
    suspend fun previewCapture(id: String) = system.previewCapture(id)
    suspend fun importAttachment(id: ConversationId, workspace: String, location: String): DataResult<Attachment> = submissionScope.async {
        val pending = PendingAttachment(nextId(), workspace, location)
        repository.beginAttachment(id, pending)
        val result = try { system.importAttachment(workspace, location) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { DataResult.Failed(AppStrings.importIncompleteRetryOrRemoveIt) }
        repository.finishAttachment(id, pending.id, result)
        result
    }.await()
    suspend fun discardAttachment(id: ConversationId, pendingId: String) = repository.discardAttachment(id, pendingId)
    suspend fun previewAttachment(workspace: String, ref: String, expanded: Boolean) = system.previewAttachment(workspace, ref, expanded)
    suspend fun attachment(workspace: String, ref: String) = system.attachment(workspace, ref)
    suspend fun restoreDraft(id: ConversationId, turn: Turn) = repository.restoreDraft(id, turn.userText, turn.attachments)
    suspend fun removeAttachment(id: ConversationId, ref: String) = repository.setAttachment(id, ref, false)
    suspend fun agents() = system.agents()
    suspend fun checkGateway(profile: GatewayProfile) = system.checkGateway(profile)
    suspend fun gateways() = system.gateways()
    suspend fun defaultGateway() = system.defaultGateway()
    suspend fun selectDefaultGateway(profile: GatewayProfile) = system.selectDefaultGateway(profile)
    suspend fun deleteGateway(id: String) = system.deleteGateway(id)
    suspend fun saveGateway(edit: GatewayEdit): GatewaySaveResult {
        val result = system.saveGateway(edit)
        if (result is GatewaySaveResult.Saved) system.gateways().filter { it.id == edit.id }.forEach { repository.updateGateway(it) }
        return result
    }
    suspend fun fetchGatewayModels(edit: GatewayEdit) = system.fetchGatewayModels(edit)
    suspend fun initialize() = system.initialize()
    suspend fun shell(command: String) = system.shell(command)
    suspend fun stopShell() = system.stopShell()
    suspend fun rename(id: ConversationId, title: String): OperationResult =
        if (title.isBlank()) OperationResult.Failed(AppStrings.titleCannotBeEmpty) else repository.rename(id, title.trim().take(120))
    suspend fun pin(id: ConversationId) = repository.pin(id)
    suspend fun project(id: ConversationId, project: String?) = repository.setProject(id, project?.trim()?.takeIf { it.isNotEmpty() })
    suspend fun archive(id: ConversationId, archived: Boolean) = repository.archive(id, archived)
    suspend fun delete(id: ConversationId, deleted: Boolean) = repository.delete(id, deleted)
    suspend fun expansion(id: TurnId, expanded: Boolean) = repository.expansion(id, expanded)
    suspend fun stepExpansion(id: TurnId, stepId: String, expanded: Boolean) = repository.stepExpansion(id, stepId, expanded)
    suspend fun anchor(id: ConversationId, messageId: String?, offset: Int) = repository.anchor(id, messageId, offset)
}
