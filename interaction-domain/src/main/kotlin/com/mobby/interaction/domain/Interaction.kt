package com.mobby.interaction.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

data class Message(val id: String, val text: String)
data class Step(val id: String, val kind: String, val summary: String, val output: String, val outcome: String?)
data class Turn(
    val id: TurnId, val userText: String, val execution: ExecutionId?, val phase: ExecutionPhase?,
    val messages: List<Message> = emptyList(), val steps: List<Step> = emptyList(),
    val diagnostics: List<Message> = emptyList(), val failure: String? = null,
    val progress: String? = null, val pending: Boolean = false, val occupied: Boolean = false,
    val expanded: Boolean? = null, val expandedSteps: Set<String> = emptySet()
)
data class ConversationSummary(val conversation: Conversation, val phase: ExecutionPhase? = null, val occupied: Boolean = false)
data class ConversationDetail(val conversation: Conversation, val turns: List<Turn>)
data class InteractionState(
    val conversations: List<ConversationSummary> = emptyList(), val selected: ConversationDetail? = null,
    val loading: Boolean = true, val error: String? = null
) {
    val occupied: ConversationSummary? get() = conversations.firstOrNull { it.occupied }
}
data class AgentOption(val agent: AgentId, val models: Map<String, Set<String>>, val unavailable: String?, val resume: Boolean, val skills: Set<String>)
data class GatewayProfile(val agent: AgentId, val id: String, val version: Long, val endpoint: String, val model: String, val protocol: String, val hasCredential: Boolean)
class GatewayEdit(val agent: AgentId, val endpoint: String, val model: String, val protocol: String, val credential: CharArray?) {
    override fun toString() = "GatewayEdit(agent=$agent)"
}
data class SystemStatus(val ready: Boolean = false, val connected: Boolean = false, val message: String = "连接中", val diagnosticBusy: Boolean = false)
data class DiagnosticOutput(val phase: ExecutionPhase?, val lines: List<String>)
sealed interface OperationResult {
    data object Done : OperationResult
    data class Failed(val message: String) : OperationResult
}
interface SystemPort {
    val status: Flow<SystemStatus>
    val diagnostic: Flow<DiagnosticOutput>
    suspend fun agents(): List<AgentOption>
    suspend fun gateways(): List<GatewayProfile>
    suspend fun saveGateway(edit: GatewayEdit): OperationResult
    suspend fun initialize(): OperationResult
    suspend fun shell(command: String): OperationResult
    suspend fun stopShell(): OperationResult
}
interface InteractionRepository : ConversationRepository {
    val state: Flow<InteractionState>
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
    private val submissionScope: CoroutineScope
) {
    val state get() = repository.state
    val status get() = system.status
    val diagnostic get() = system.diagnostic
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
    suspend fun agents() = system.agents()
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
