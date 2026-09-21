package com.mobby.interaction.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

data class Message(val id: String, val text: String)
data class Step(val id: String, val kind: String, val summary: String, val output: String, val outcome: String?)
data class SkillProposal(val ref: String, val markdown: String, val agent: AgentId)
data class Turn(
    val id: TurnId, val userText: String, val execution: ExecutionId?, val phase: ExecutionPhase?,
    val messages: List<Message> = emptyList(), val steps: List<Step> = emptyList(),
    val diagnostics: List<Message> = emptyList(), val failure: String? = null,
    val progress: String? = null, val pending: Boolean = false, val occupied: Boolean = false,
    val expanded: Boolean? = null, val expandedSteps: Set<String> = emptySet(),
    val skillProposals: List<SkillProposal> = emptyList(), val creatingSkill: Boolean = false, val proposalsLoading: Boolean = false, val attachments: List<String> = emptyList(),
    val permissions: List<PermissionRequest> = emptyList()
)
data class ConversationSummary(val conversation: Conversation, val phase: ExecutionPhase? = null, val occupied: Boolean = false)
data class ConversationDetail(val conversation: Conversation, val turns: List<Turn>, val hasEarlier: Boolean = false)
data class InteractionState(
    val conversations: List<ConversationSummary> = emptyList(), val selected: ConversationDetail? = null,
    val loading: Boolean = true, val error: String? = null
) {
    val occupied: ConversationSummary? get() = conversations.firstOrNull { it.occupied }
}
data class AgentOption(val agent: AgentId, val models: Map<String, Set<String>>, val unavailable: String?, val resume: Boolean, val skills: Set<String>, val resources: Boolean = false, val images: Boolean = false, val approvals: Boolean = false)
data class GatewayProfile(val agent: AgentId, val id: String, val version: Long, val endpoint: String, val model: String, val protocol: String, val hasCredential: Boolean)
data class GatewayCheckReport(val passed: Boolean, val message: String)
class GatewayEdit(val agent: AgentId, val endpoint: String, val model: String, val protocol: String, val credential: CharArray?) {
    override fun toString() = "GatewayEdit(agent=$agent)"
}
data class SystemStatus(val ready: Boolean = false, val connected: Boolean = false, val message: String = "连接中", val diagnosticBusy: Boolean = false)
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
data class EventHistoryLimits(val days: Int = 30, val mib: Int = 32, val outputDays: Int = 30, val outputMiB: Int = 256, val attachmentMiB: Int = 512)
data class WorkspaceOption(val ref: String, val name: String)
interface SystemPort {
    suspend fun workspaces(): DataResult<List<WorkspaceOption>> = DataResult.Failed("当前执行端不支持工作区选择")
    suspend fun createWorkspace(name: String): DataResult<WorkspaceOption> = DataResult.Failed("当前执行端不支持创建工作区")
    suspend fun eventHistoryLimits(): DataResult<EventHistoryLimits> = DataResult.Failed("当前执行端不支持存储设置")
    suspend fun saveEventHistoryLimits(value: EventHistoryLimits): OperationResult = OperationResult.Failed("当前执行端不支持存储设置")
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
    suspend fun saveGateway(edit: GatewayEdit): OperationResult
    suspend fun initialize(): OperationResult
    suspend fun shell(command: String): OperationResult
    suspend fun stopShell(): OperationResult
}
interface InteractionRepository : ConversationRepository {
    suspend fun saveSkillProposal(proposal: SkillProposal, markdown: String): DataResult<Skill> = DataResult.Failed("当前会话不支持保存生成草稿")
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
    suspend fun setProject(id: ConversationId, project: String?)
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
    suspend fun createWorkspace(name: String) = submissionScope.async { system.createWorkspace(name) }.await()
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
    suspend fun create(config: NextTurnConfig) = repository.create(config)
    suspend fun draft(id: ConversationId, text: String, start: Int, end: Int) = repository.editDraft(id, text, start, end)
    suspend fun configure(id: ConversationId, config: NextTurnConfig) = repository.configure(id, config)
    suspend fun prepareSend(id: ConversationId) = repository.prepareTurn(id, TurnId(nextId()))
    suspend fun sendPrepared(turn: TurnExecution): Submission = submissionScope.async {
        execution.submit(turn).also { repository.recordSubmission(turn, it) }
    }.await()
    suspend fun reconcile(id: ConversationId) = SubmitTurnUseCase(repository, execution).reconcile(id)
    suspend fun stop(id: ExecutionId) = StopRunUseCase(execution)(id)
    suspend fun resolvePermission(decision: PermissionDecision) = submissionScope.async { execution.resolvePermission(decision) }.await()
    suspend fun skills(agent: AgentId) = system.skills(agent)
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
                ?: return OperationResult.Failed("技能目录不可用，请重试")
            if (current.none { it.ref == skill.ref && it.available }) return OperationResult.Failed("技能已改变或不可用，请重新选择")
        }
        repository.setSkill(id, skill.ref, enabled)
        return OperationResult.Done
    }
    suspend fun removeSkill(id: ConversationId, ref: String) = repository.setSkill(id, ref, false)
    suspend fun createSkillConversation(id: ConversationId, agent: AgentId): DataResult<ConversationId> {
        val creator = (system.skills(agent) as? DataResult.Loaded)?.value?.firstOrNull { it.name == "skill-creator" && it.available }
            ?: return DataResult.Failed("当前 Agent 没有可用的 Skill Creator；请返回技能页选择其他创建方式")
        return DataResult.Loaded(repository.createSkillConversation(id, creator.ref))
    }
    suspend fun beginCapture(conversation: ConversationId, workspace: String): DataResult<CameraCapture> {
        repository.awaitAttachmentRecovery()
        val current = repository.conversation(conversation)
        if (current.archived || current.deleted || current.config.workspace != workspace || current.draft.pendingAttachment != null || current.draft.attachments.size >= 4)
            return DataResult.Failed("当前会话不能添加照片")
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
            catch (_: Exception) { DataResult.Failed("导入未完成，请重试或移除") }
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
    suspend fun saveGateway(edit: GatewayEdit): OperationResult {
        val result = system.saveGateway(edit)
        if (result == OperationResult.Done) system.gateways().firstOrNull { it.agent == edit.agent }?.let { repository.updateGateway(it) }
        return result
    }
    suspend fun initialize() = system.initialize()
    suspend fun shell(command: String) = system.shell(command)
    suspend fun stopShell() = system.stopShell()
    suspend fun rename(id: ConversationId, title: String): OperationResult =
        if (title.isBlank()) OperationResult.Failed("标题不能为空") else repository.rename(id, title.trim().take(120))
    suspend fun pin(id: ConversationId) = repository.pin(id)
    suspend fun project(id: ConversationId, project: String?) = repository.setProject(id, project?.trim()?.takeIf { it.isNotEmpty() })
    suspend fun archive(id: ConversationId, archived: Boolean) = repository.archive(id, archived)
    suspend fun delete(id: ConversationId, deleted: Boolean) = repository.delete(id, deleted)
    suspend fun expansion(id: TurnId, expanded: Boolean) = repository.expansion(id, expanded)
    suspend fun stepExpansion(id: TurnId, stepId: String, expanded: Boolean) = repository.stepExpansion(id, stepId, expanded)
    suspend fun anchor(id: ConversationId, messageId: String?, offset: Int) = repository.anchor(id, messageId, offset)
}
