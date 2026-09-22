package com.github.ytlog.mobby.android.interaction.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConversationRulesTest {
    private val config = NextTurnConfig(AgentId.CODEX, "model", "low", "workspace", "gateway-v1")
    private val original = Conversation(ConversationId("original"), config,
        Draft(7, "draft", attachments = listOf("file"), capabilities = setOf("skill")), true, "session", project = "Project A")
    private val accepted = Submission.Accepted(ExecutionId("execution"))

    @Test fun `only accepted matching draft is cleared`() {
        val draft = original.draft
        assertEquals(draft, ConversationRules.afterSubmission(draft, 7, Submission.Rejected(Failure.BUSY)))
        assertEquals(draft, ConversationRules.afterSubmission(draft, 7, Submission.Unconfirmed))
        assertEquals(draft, ConversationRules.afterSubmission(draft, 6, accepted))
        assertEquals(Draft(revision = 8), ConversationRules.afterSubmission(draft, 7, accepted))
    }
    @Test fun `agent switch stays in the conversation and parks each engine session`() {
        val switched = ConversationRules.applyConfig(original, config.copy(agent = AgentId.CLAUDE_CODE))
        assertEquals(original.id, switched.id)
        assertEquals(original.project, switched.project)
        assertEquals(original.config.workspace, switched.config.workspace)
        assertEquals("draft", switched.draft.text)
        assertEquals(listOf("file"), switched.draft.attachments)
        assertTrue(switched.draft.capabilities.isEmpty())
        assertTrue(switched.hasTurns)
        assertNull(switched.session)
        assertEquals(mapOf(AgentId.CODEX to "session"), switched.sessions)
        assertEquals("session", original.session)
        val claude = switched.copy(session = "claude-session")
        val back = ConversationRules.applyConfig(claude, config)
        assertEquals("session", back.session)
        assertEquals(mapOf(AgentId.CODEX to "session", AgentId.CLAUDE_CODE to "claude-session"), back.sessions)
        val parked = ConversationRules.rememberSession(switched, AgentId.CODEX, "later-codex")
        assertNull(parked.session)
        assertEquals("later-codex", parked.sessions[AgentId.CODEX])
        assertEquals(original, ConversationRules.rememberSession(original, AgentId.CODEX, "not a session"))
    }
    @Test fun `empty conversation agent switch keeps content but clears agent bound skills`() {
        val empty = original.copy(hasTurns = false, session = null, creator = "skill")
        val changed = ConversationRules.applyConfig(empty, config.copy(agent = AgentId.CLAUDE_CODE))
        assertEquals(empty.id, changed.id)
        assertEquals(empty.draft.copy(revision = empty.draft.revision + 1, capabilities = emptySet()), changed.draft)
        assertEquals(empty.project, changed.project)
        assertNull(changed.creator)
        assertEquals(setOf("skill"), empty.draft.capabilities)
        val withPlugin = empty.copy(draft = empty.draft.copy(capabilities = setOf("skill", "plugin:PHONE:ACCESSIBILITY")))
        val kept = ConversationRules.applyConfig(withPlugin, config.copy(agent = AgentId.CLAUDE_CODE))
        assertEquals(setOf("plugin:PHONE:ACCESSIBILITY"), kept.draft.capabilities)
        assertEquals(withPlugin.draft.revision + 1, kept.draft.revision)
        val pluginOnly = empty.copy(draft = empty.draft.copy(capabilities = setOf("plugin:PHONE:ACCESSIBILITY")))
        val unchanged = ConversationRules.applyConfig(pluginOnly, config.copy(agent = AgentId.CLAUDE_CODE))
        assertEquals(pluginOnly.draft, unchanged.draft)
        val next = ConversationRules.applyConfig(original, config.copy(model = "next"))
        assertEquals(original.id, next.id)
        assertEquals(original.session, next.session)
        assertEquals(mapOf(AgentId.CODEX to "session"), next.sessions)
    }
    @Test fun `workspace can change before first turn but cannot move existing history`() {
        val changedConfig = config.copy(workspace = "other-workspace")
        val changed = ConversationRules.applyConfig(original.copy(hasTurns = false, draft = original.draft.copy(attachments = emptyList())), changedConfig)
        assertEquals("other-workspace", changed.config.workspace)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationRules.applyConfig(original, changedConfig)
        }
    }
    @Test fun `workspace change cannot strand imported or pending attachments`() {
        for (draft in listOf(original.draft, Draft(pendingAttachment = PendingAttachment("p", config.workspace, "content://file")))) {
            assertThrows(IllegalArgumentException::class.java) {
                ConversationRules.applyConfig(original.copy(hasTurns = false, draft = draft), config.copy(workspace = "next"))
            }
        }
    }
    @Test fun `skill creator makes independent prefilled conversation only when available`() {
        assertNull(ConversationRules.createSkillConversation(original, ConversationId("skill"), null))
        val skill = ConversationRules.createSkillConversation(original, ConversationId("skill"), "creator")!!
        assertEquals(original.config, skill.config)
        assertEquals(original.project, skill.project)
        assertNull(skill.session)
        assertFalse(skill.hasTurns)
        assertEquals("请用 /skill-creator 帮我创建技能，要求是：", skill.draft.text)
        assertEquals(skill.draft.text.length, skill.draft.selectionStart)
        assertEquals(setOf("creator"), skill.draft.capabilities)
        assertTrue(skill.draft.attachments.isEmpty())
        assertEquals("draft", original.draft.text)
    }
    @Test fun `adjacent tool calls group between replies and thinking is not a step`() {
        val turn = Turn(TurnId("t"), "打开应用商店", null, ExecutionPhase.RUNNING, occupied = true,
            messages = listOf(Message("a", "先看屏幕", 0), Message("b", "再打开商店", 3)),
            steps = listOf(
                Step("s1", "snapshot", "snapshot", "ok", "SUCCEEDED", 1),
                Step("s2", "click", "click", "ok", "SUCCEEDED", 2),
                Step("s3", "recents", "recents", "", null, 4)))
        val entries = turn.transcript()
        assertEquals("先看屏幕", (entries[0] as TranscriptEntry.Reply).message.text)
        assertEquals(listOf("s1", "s2"), (entries[1] as TranscriptEntry.ToolRun).steps.map { it.id })
        assertEquals("再打开商店", (entries[2] as TranscriptEntry.Reply).message.text)
        assertEquals(listOf("s3"), (entries[3] as TranscriptEntry.ToolRun).steps.map { it.id })
        assertEquals(4, entries.size)
    }
    @Test fun `last reply in the turn is the only place for reply actions`() {
        val turn = Turn(TurnId("t"), "任务", null, ExecutionPhase.SUCCEEDED,
            messages = listOf(Message("a", "中间说明", 0), Message("b", "最终回复", 2)),
            steps = listOf(Step("s1", "bash", "ls", "ok", "SUCCEEDED", 1)))
        val replies = turn.transcript().filterIsInstance<TranscriptEntry.Reply>()
        assertEquals(listOf("a", "b"), replies.map { it.message.id })
        assertEquals("b", replies.last().message.id)
        assertEquals("中间说明\n最终回复", turn.messages.joinToString("\n") { it.text })
        assertTrue(turn.copy(messages = emptyList()).transcript().none { it is TranscriptEntry.Reply })
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
