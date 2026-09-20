package com.mobby.interaction.data

import androidx.test.core.app.ApplicationProvider
import com.mobby.interaction.domain.*
import com.mobby.interaction.domain.AgentId as DomainAgent
import com.mobby.runtime.api.*
import com.mobby.runtime.api.AgentId as RuntimeAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RoomInteractionRepositoryTest {
    private lateinit var db: InteractionDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: RoomInteractionRepository
    private val runtime = TestRuntime()
    private val system = object : SystemPort {
        override suspend fun skills(agent: DomainAgent) = DataResult.Loaded(emptyList<Skill>())
        override suspend fun readSkill(ref: String) = DataResult.Failed("unavailable")
        override suspend fun previewSkill(markdown: String) = DataResult.Failed("unavailable")
        override suspend fun previewManualSkill(agent: DomainAgent, name: String, description: String, body: String) = DataResult.Failed("unavailable")
        override suspend fun readSkillImport(location: String) = DataResult.Failed("unavailable")
        override suspend fun importSkill(agent: DomainAgent, markdown: String) = DataResult.Failed("unavailable")
        override suspend fun saveManualSkill(agent: DomainAgent, name: String, description: String, body: String) = DataResult.Failed("unavailable")
        override val status = flowOf(SystemStatus(true, true, "ready"))
        override val diagnostic = flowOf(DiagnosticOutput(null, emptyList()))
        override suspend fun agents() = emptyList<AgentOption>()
        override suspend fun gateways() = listOf(GatewayProfile(DomainAgent.CODEX, "CODEX", 0, "", "test-model", "RESPONSES", false))
        override suspend fun saveGateway(edit: GatewayEdit) = OperationResult.Done
        override suspend fun initialize() = OperationResult.Done
        override suspend fun shell(command: String) = OperationResult.Done
        override suspend fun stopShell() = OperationResult.Done
    }
    @Before fun setUp() { start() }
    private fun start() {
        db = InteractionDatabase.open(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = RoomInteractionRepository(db, runtime, system, scope, RuntimeExecutionAdapter(runtime))
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private suspend fun state(predicate: (InteractionState) -> Boolean = { it.selected != null }) = withTimeout(10_000) { repository.state.first(predicate) }
    @Test fun `accepted turn preserves newly typed draft and rejected send keeps text`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "first", 5, 5)
        val prepared = (repository.prepareTurn(c.id, TurnId("first")) as PrepareTurnResult.Prepared).turn
        repository.editDraft(c.id, "next", 4, 4)
        runtime.admit(prepared)
        repository.recordSubmission(prepared, Submission.Accepted(ExecutionId("first")))
        val accepted = state { it.selected?.turns?.singleOrNull()?.execution != null }
        assertEquals("next", accepted.selected!!.conversation.draft.text)
        assertEquals("first", accepted.selected!!.turns.single().userText)
        repository.recordSubmission(prepared, Submission.Accepted(ExecutionId("first")))
        assertEquals("next", db.dao().conversation(c.id.value)!!.domain().draft.text)
        val second = (repository.prepareTurn(c.id, TurnId("second")) as PrepareTurnResult.Prepared).turn
        repository.recordSubmission(second, Submission.Rejected(Failure.BUSY))
        assertEquals("next", db.dao().conversation(c.id.value)!!.domain().draft.text)
        assertFalse(db.dao().turn("second")!!.occupied)
    }
    @Test fun `conversation selection keeps each draft and cross agent starts fresh context`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "draft A", 3, 3)
        val second = repository.create(c.config)
        repository.editDraft(second, "draft B", 7, 7)
        repository.select(c.id)
        val restored = state { it.selected?.conversation?.id == c.id && it.selected!!.conversation.draft.text == "draft A" }
        assertEquals(3, restored.selected!!.conversation.draft.selectionStart)
        val turn = (repository.prepareTurn(c.id, TurnId("pending")) as PrepareTurnResult.Prepared).turn
        repository.recordSubmission(turn, Submission.Rejected(Failure.BUSY))
        val new = repository.configure(c.id, c.config.copy(agent = DomainAgent.CLAUDE_CODE, gatewayProfile = "CLAUDE"))
        assertNotEquals(c.id, new)
        val created = db.dao().conversation(new.value)!!.domain()
        assertEquals("draft A", created.draft.text)
        assertNull(created.session)
        assertEquals("draft B", db.dao().conversation(second.value)!!.domain().draft.text)
    }
    @Test fun `active conversation cannot be archived or deleted and expansion is persistent`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "run", 3, 3)
        val turn = (repository.prepareTurn(c.id, TurnId("turn")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("turn")))
        assertTrue(repository.archive(c.id, true) is OperationResult.Failed)
        assertTrue(repository.delete(c.id, true) is OperationResult.Failed)
        repository.expansion(turn.turnId, true)
        repository.stepExpansion(turn.turnId, "step", true)
        repository.anchor(c.id, "run:turn", 21)
        assertEquals(true, db.dao().turn("turn")!!.expanded)
        assertEquals(setOf("step"), storageJson.decodeFromString<Set<String>>(db.dao().turn("turn")!!.expandedSteps))
        assertEquals(21, db.dao().conversation(c.id.value)!!.domain().anchorOffset)
    }
    @Test fun `application recreation finds original request without resending or clearing newer draft`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "first", 5, 5)
        val prepared = (repository.prepareTurn(c.id, TurnId("lost-response")) as PrepareTurnResult.Prepared).turn
        runtime.admit(prepared)
        repository.recordSubmission(prepared, Submission.Unconfirmed)
        repository.editDraft(c.id, "new draft", 9, 9)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        start()
        val restored = state { it.selected?.turns?.singleOrNull()?.execution != null }
        assertEquals(ExecutionId("lost-response"), restored.selected!!.turns.single().execution)
        assertEquals("new draft", restored.selected!!.conversation.draft.text)
        assertEquals(0, runtime.submissions)
    }
    @Test fun `gateway save changes future config but never mutates already prepared turn`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "run", 3, 3)
        val prepared = (repository.prepareTurn(c.id, TurnId("turn")) as PrepareTurnResult.Prepared).turn
        repository.updateGateway(GatewayProfile(DomainAgent.CODEX, "CODEX", 2, "", "new-model", "RESPONSES", false))
        assertEquals("test-model", db.dao().turn("turn")!!.execution().config.model)
        assertEquals("new-model", db.dao().conversation(c.id.value)!!.domain().config.model)
        assertEquals(0L, prepared.config.gatewayVersion)
    }
    @Test fun `skill choice is draft scoped and creator conversation preserves original draft`() = runBlocking {
        val c = state().selected!!.conversation
        val skill = "skill:CODEX:USER:review:hash"
        repository.editDraft(c.id, "original", 4, 6)
        repository.setSkill(c.id, skill, true)
        val prepared = (repository.prepareTurn(c.id, TurnId("skill-run")) as PrepareTurnResult.Prepared).turn
        repository.setSkill(c.id, skill, false)
        assertEquals(setOf(skill), prepared.draft.capabilities)
        assertTrue(db.dao().conversation(c.id.value)!!.domain().draft.capabilities.isEmpty())
        val created = repository.createSkillConversation(c.id, "skill:CODEX:BUILTIN:skill-creator:hash")
        val original = db.dao().conversation(c.id.value)!!.domain()
        val creator = db.dao().conversation(created.value)!!.domain()
        assertEquals("original", original.draft.text)
        assertEquals(4, original.draft.selectionStart)
        assertNull(creator.session)
        assertEquals("创建技能", creator.title)
        assertEquals("请用 /skill-creator 帮我创建技能，要求是：", creator.draft.text)
        assertEquals(creator.draft.text.length, creator.draft.selectionStart)
        assertEquals(setOf("skill:CODEX:BUILTIN:skill-creator:hash"), creator.draft.capabilities)
    }
    private class TestRuntime : RuntimeClient {
        override val connection = MutableStateFlow(ConnectionState.CONNECTED)
        val snapshots = mutableMapOf<String, RunSnapshot>()
        var submissions = 0
        fun admit(turn: TurnExecution) {
            val request = turn.request()
            snapshots[turn.turnId.value] = RunSnapshot(RunId(turn.turnId.value), RunPhase.RUNNING, 1, 1,
                RunConfigSnapshot(request.agentId, request.workspaceRef, request.modelId, request.reasoningLevel, request.gatewayProfileRef, request.capabilityRefs))
        }
        override suspend fun capabilities() = CapabilityResult.Available(RuntimeCapabilities("test", emptyList()))
        override suspend fun submit(request: RunRequest): SubmitResult { submissions++; return SubmitResult.Rejected(RuntimeError(ErrorCode.BUSY)) }
        override suspend fun findByRequest(requestId: RequestId): RequestLookup = if (requestId.value in snapshots) RequestLookup.Found(RunId(requestId.value)) else RequestLookup.NotFound
        override suspend fun cancel(request: CancelRequest) = CommandResult.Accepted
        override suspend fun resolveApproval(request: ApprovalDecision) = CommandResult.Rejected(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
        override suspend fun snapshot(runId: RunId): SnapshotResult = snapshots[runId.value]?.let { SnapshotResult.Found(it) } ?: SnapshotResult.Unavailable(RuntimeError(ErrorCode.NOT_FOUND))
        override fun observe(runId: RunId, after: EventCursor?): Flow<RuntimeUpdate> = flow {
            snapshots[runId.value]?.let { emit(RuntimeUpdate.Baseline(it, EventCursor(runId, it.lastSequence))) }
            awaitCancellation()
        }
        override suspend fun readArtifact(request: ArtifactReadRequest) = ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
    }
}
