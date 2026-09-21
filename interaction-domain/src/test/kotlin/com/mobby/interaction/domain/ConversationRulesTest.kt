package com.mobby.interaction.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConversationRulesTest {
    private val config = NextTurnConfig(AgentId.CODEX, "model", "low", "workspace", "gateway-v1")
    private val original = Conversation(ConversationId("original"), config,
        Draft(7, "draft", attachments = listOf("file"), capabilities = setOf("skill")), true, "session")
    private val accepted = Submission.Accepted(ExecutionId("execution"))

    @Test fun `only accepted matching draft is cleared`() {
        val draft = original.draft
        assertEquals(draft, ConversationRules.afterSubmission(draft, 7, Submission.Rejected(Failure.BUSY)))
        assertEquals(draft, ConversationRules.afterSubmission(draft, 7, Submission.Unconfirmed))
        assertEquals(draft, ConversationRules.afterSubmission(draft, 6, accepted))
        assertEquals(Draft(revision = 8), ConversationRules.afterSubmission(draft, 7, accepted))
    }
    @Test fun `cross agent creates conversation with text only and leaves original intact`() {
        val switched = ConversationRules.applyConfig(original, config.copy(agent = AgentId.CLAUDE_CODE), ConversationId("new"))
        assertEquals("new", switched.id.value)
        assertEquals("draft", switched.draft.text)
        assertTrue(switched.draft.attachments.isEmpty())
        assertTrue(switched.draft.capabilities.isEmpty())
        assertNull(switched.session)
        assertFalse(switched.hasTurns)
        assertEquals(listOf("file"), original.draft.attachments)
        assertEquals("session", original.session)
    }
    @Test fun `empty conversation agent switch retains its draft and same agent config retains session`() {
        val empty = original.copy(hasTurns = false, session = null)
        val changed = ConversationRules.applyConfig(empty, config.copy(agent = AgentId.CLAUDE_CODE), ConversationId("unused"))
        assertEquals(empty.id, changed.id)
        assertEquals(empty.draft, changed.draft)
        val next = ConversationRules.applyConfig(original, config.copy(model = "next"), ConversationId("unused"))
        assertEquals(original.id, next.id)
        assertEquals(original.session, next.session)
    }
    @Test fun `workspace can change before first turn but cannot move existing history`() {
        val changedConfig = config.copy(workspace = "other-workspace")
        val changed = ConversationRules.applyConfig(original.copy(hasTurns = false, draft = original.draft.copy(attachments = emptyList())), changedConfig, ConversationId("unused"))
        assertEquals("other-workspace", changed.config.workspace)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationRules.applyConfig(original, changedConfig, ConversationId("unused"))
        }
    }
    @Test fun `workspace change cannot strand imported or pending attachments`() {
        for (draft in listOf(original.draft, Draft(pendingAttachment = PendingAttachment("p", config.workspace, "content://file")))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConversationRules.applyConfig(original.copy(hasTurns = false, draft = draft), config.copy(workspace = "next"), ConversationId("unused"))
            }
        }
    }
    @Test fun `skill creator makes independent prefilled conversation only when available`() {
        assertNull(ConversationRules.createSkillConversation(original, ConversationId("skill"), null))
        val skill = ConversationRules.createSkillConversation(original, ConversationId("skill"), "creator")!!
        assertEquals(original.config, skill.config)
        assertEquals("请用 /skill-creator 帮我创建技能，要求是：", skill.draft.text)
        assertEquals(skill.draft.text.length, skill.draft.selectionStart)
        assertEquals(setOf("creator"), skill.draft.capabilities)
        assertTrue(skill.draft.attachments.isEmpty())
        assertEquals("draft", original.draft.text)
    }
    @Test fun `manual expansion and active reading override success collapse`() {
        for (phase in ExecutionPhase.values()) {
            assertTrue(ExecutionExpansion(true).expanded(phase))
            assertFalse(ExecutionExpansion(false).expanded(phase))
            assertEquals(phase != ExecutionPhase.SUCCEEDED, ExecutionExpansion().expanded(phase))
        }
        assertTrue(ExecutionExpansion().expanded(ExecutionPhase.SUCCEEDED, readingDetails = true))
    }
    @Test fun `submit freezes config and preserves text typed while waiting`() = runTest {
        val repository = MemoryRepository(original)
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Submission>()
        var submitted: TurnExecution? = null
        val execution = object : StubExecution() {
            override suspend fun submit(turn: TurnExecution): Submission { submitted = turn; started.complete(Unit); return response.await() }
        }
        val useCase = SubmitTurnUseCase(repository, execution)
        val result = async { useCase(original.id, TurnId("turn")) }
        started.await()
        repository.current = original.copy(config = config.copy(model = "next"), draft = Draft(8, "next draft"))
        assertEquals(Submission.Rejected(Failure.PENDING_SUBMISSION), useCase(original.id, TurnId("duplicate")))
        response.complete(accepted)
        assertEquals(accepted, result.await())
        assertEquals("next draft", repository.current.draft.text)
        assertEquals(config, submitted!!.config)
    }
    @Test fun `lost response reconciles same request without resubmit`() = runTest {
        val repository = MemoryRepository(original)
        var sends = 0; var lookedUp: TurnId? = null
        val execution = object : StubExecution() {
            override suspend fun submit(turn: TurnExecution): Submission { sends++; return Submission.Unconfirmed }
            override suspend fun lookup(turnId: TurnId): Submission { lookedUp = turnId; return accepted }
        }
        val useCase = SubmitTurnUseCase(repository, execution)
        assertEquals(Submission.Unconfirmed, useCase(original.id, TurnId("stable")))
        assertEquals(original.draft, repository.current.draft)
        assertEquals(accepted, useCase.reconcile(original.id))
        assertEquals(TurnId("stable"), lookedUp)
        assertEquals(1, sends)
        assertEquals("", repository.current.draft.text)
    }
    @Test fun `busy retains draft and stop returns acceptance without fabricating terminal state`() = runTest {
        val repository = MemoryRepository(original)
        val execution = object : StubExecution() {
            override suspend fun submit(turn: TurnExecution) = Submission.Rejected(Failure.BUSY, ExecutionId("other"))
        }
        val result = SubmitTurnUseCase(repository, execution)(original.id, TurnId("turn"))
        assertEquals(Failure.BUSY, (result as Submission.Rejected).reason)
        assertEquals(original.draft, repository.current.draft)
        assertEquals(StopResult.Accepted, StopRunUseCase(execution)(ExecutionId("other")))
    }
    private open class StubExecution : ExecutionPort {
        override suspend fun submit(turn: TurnExecution): Submission = error("not configured")
        override suspend fun lookup(turnId: TurnId): Submission = Submission.Unconfirmed
        override suspend fun cancel(executionId: ExecutionId): StopResult = StopResult.Accepted
        override fun observe(executionId: ExecutionId) = emptyFlow<ExecutionFact>()
    }
    /** Models the repository's atomic transaction contract; production Room comes in stage 3. */
    private class MemoryRepository(var current: Conversation) : ConversationRepository {
        private var pending: TurnExecution? = null
        override suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult {
            check(current.id == conversationId)
            if (pending != null) return PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
            if (current.draft.text.isBlank() && current.draft.attachments.isEmpty()) return PrepareTurnResult.Rejected(Failure.EMPTY_DRAFT)
            return TurnExecution(turnId, conversationId, current.draft, current.config, current.session).let {
                pending = it; PrepareTurnResult.Prepared(it)
            }
        }
        override suspend fun recordSubmission(turn: TurnExecution, result: Submission) {
            check(pending?.turnId == turn.turnId)
            current = current.copy(draft = ConversationRules.afterSubmission(current.draft, turn.draft.revision, result))
            if (result != Submission.Unconfirmed) pending = null
        }
        override suspend fun pendingTurn(conversationId: ConversationId) = pending?.takeIf { it.conversationId == conversationId }
    }
}
