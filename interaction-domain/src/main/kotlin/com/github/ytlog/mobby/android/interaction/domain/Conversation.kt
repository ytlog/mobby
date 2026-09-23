package com.github.ytlog.mobby.android.interaction.domain

import kotlinx.coroutines.flow.Flow

@JvmInline value class ConversationId(val value: String)
@JvmInline value class TurnId(val value: String)
@JvmInline value class ExecutionId(val value: String)
enum class AgentId { CODEX, CLAUDE_CODE, OPEN_CODE }

data class NextTurnConfig(val agent: AgentId, val model: String, val reasoning: String?, val workspace: String, val gatewayProfile: String, val gatewayVersion: Long = 0)
data class PendingAttachment(val id: String, val workspace: String, val location: String, val error: String? = null)
data class Draft(
    val revision: Long = 0, val text: String = "", val selectionStart: Int = text.length,
    val selectionEnd: Int = selectionStart, val attachments: List<String> = emptyList(),
    val capabilities: Set<String> = emptySet(), val pendingAttachment: PendingAttachment? = null
) {
    init { require(selectionStart in 0..text.length); require(selectionEnd in 0..text.length) }
}
data class Conversation(
    val id: ConversationId, val config: NextTurnConfig, val draft: Draft = Draft(),
    val hasTurns: Boolean = false, val session: String? = null,
    val title: String = "新对话", val pinned: Boolean = false, val project: String? = null,
    val archived: Boolean = false, val deleted: Boolean = false,
    val anchor: String? = null, val anchorOffset: Int = 0, val updatedAt: Long = 0, val creator: String? = null,
    val sessions: Map<AgentId, String> = emptyMap()
)
data class TurnExecution(val turnId: TurnId, val conversationId: ConversationId, val draft: Draft, val config: NextTurnConfig, val session: String?, val creatingSkill: Boolean = false)
enum class Failure { PENDING_ATTACHMENT, INPUT_TOO_LARGE, BUSY, INVALID_CONFIG, UNSUPPORTED_CAPABILITY, UNAVAILABLE, EMPTY_DRAFT, PENDING_SUBMISSION }
sealed interface Submission {
    data class Accepted(val executionId: ExecutionId) : Submission
    data class Rejected(val reason: Failure, val activeExecution: ExecutionId? = null) : Submission
    /** Response lost: retain this request and look it up, never generate a replacement request. */
    data object Unconfirmed : Submission
}
sealed interface StopResult {
    data object Accepted : StopResult
    data object AlreadyTerminal : StopResult
    data class Rejected(val reason: Failure) : StopResult
}
enum class ExecutionPhase { ACCEPTED, RUNNING, AWAITING_APPROVAL, CANCELLING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED, OUTCOME_UNKNOWN }
enum class ProgressNotice { OUTPUT_TRUNCATED }
data class ExecutionFact(val executionId: ExecutionId, val phase: ExecutionPhase)
sealed interface PermissionSubject {
    data class Command(val command: String) : PermissionSubject
    data class FileRead(val path: String, val offset: String = "", val limit: String = "") : PermissionSubject
    data class FileWrite(val path: String, val content: String) : PermissionSubject
    data class FileDiff(val paths: List<String>, val diff: String) : PermissionSubject
    data class Action(val name: String, val detail: String) : PermissionSubject
}
data class PermissionRequest(val id: String, val revision: Long, val subject: PermissionSubject)
data class PermissionKey(val execution: ExecutionId, val approvalId: String, val revision: Long)
data class PermissionDecision(val commandId: String, val key: PermissionKey, val allow: Boolean)
interface ExecutionPort {
    suspend fun submit(turn: TurnExecution): Submission
    suspend fun lookup(turnId: TurnId): Submission
    suspend fun cancel(executionId: ExecutionId): StopResult
    suspend fun resolvePermission(decision: PermissionDecision): OperationResult = OperationResult.Failed("当前执行端不支持审批")
    fun observe(executionId: ExecutionId): Flow<ExecutionFact>
}
sealed interface PrepareTurnResult {
    data class Prepared(val turn: TurnExecution) : PrepareTurnResult
    data class Rejected(val reason: Failure) : PrepareTurnResult
}
interface ConversationRepository {
    /** Atomic: freeze draft/config and persist pending request. Reject if one is unresolved. */
    suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult
    /** Atomic: persist result; clear only matching draft revision on Accepted. Keep unconfirmed pending. */
    suspend fun recordSubmission(turn: TurnExecution, result: Submission)
    suspend fun pendingTurn(conversationId: ConversationId): TurnExecution?
}
class SubmitTurnUseCase(private val repository: ConversationRepository, private val execution: ExecutionPort) {
    suspend operator fun invoke(conversationId: ConversationId, turnId: TurnId): Submission =
        when (val prepared = repository.prepareTurn(conversationId, turnId)) {
            is PrepareTurnResult.Rejected -> Submission.Rejected(prepared.reason)
            is PrepareTurnResult.Prepared -> execution.submit(prepared.turn).also { repository.recordSubmission(prepared.turn, it) }
        }

    suspend fun reconcile(conversationId: ConversationId): Submission? {
        val pending = repository.pendingTurn(conversationId) ?: return null
        return execution.lookup(pending.turnId).also { repository.recordSubmission(pending, it) }
    }
}
class StopRunUseCase(private val execution: ExecutionPort) {
    suspend operator fun invoke(executionId: ExecutionId): StopResult = execution.cancel(executionId)
}

/** Pure interaction policies, shared by future repository and Compose projections. */
object ConversationRules {
    fun afterSubmission(current: Draft, submittedRevision: Long, result: Submission): Draft =
        if (result is Submission.Accepted && current.revision == submittedRevision) Draft(revision = current.revision + 1) else current

    /** Record one engine's CLI session. The active session changes only when that engine is currently selected. */
    fun rememberSession(current: Conversation, agent: AgentId, id: String): Conversation {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return current
        val sessions = current.sessions + (agent to id)
        return current.copy(sessions = sessions, session = if (current.config.agent == agent) id else current.session)
    }

    fun applyConfig(current: Conversation, config: NextTurnConfig): Conversation {
        require(config.workspace == current.config.workspace || current.draft.attachments.isEmpty() && current.draft.pendingAttachment == null) { "Remove draft attachments before changing workspace" }
        require(!current.hasTurns || config.workspace == current.config.workspace) { "Existing workspace must be preserved" }
        val sameAgent = current.config.agent == config.agent
        val kept = if (sameAgent) current.draft.capabilities else current.draft.capabilities.filter { it.startsWith("plugin:device:") }.toSet()
        val sessions = if (current.session == null) current.sessions else current.sessions + (current.config.agent to current.session)
        return current.copy(config = config,
            draft = if (kept != current.draft.capabilities)
                current.draft.copy(revision = current.draft.revision + 1, capabilities = kept) else current.draft,
            session = if (sameAgent) current.session else sessions[config.agent],
            sessions = sessions,
            creator = if (sameAgent) current.creator else null)
    }

    fun createSkillConversation(current: Conversation, newId: ConversationId, creator: String?): Conversation? {
        if (creator == null) return null
        require(newId != current.id)
        return Conversation(newId, current.config, Draft(
            text = "请用 /skill-creator 帮我创建技能，要求是：", capabilities = setOf(creator)
        ), project = current.project, creator = creator)
    }
}
