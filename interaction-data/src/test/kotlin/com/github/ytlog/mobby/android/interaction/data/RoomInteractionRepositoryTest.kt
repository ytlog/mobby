package com.github.ytlog.mobby.android.interaction.data

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.domain.AgentId as DomainAgent
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.api.AgentId as RuntimeAgent
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
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
    private var importGate: CompletableDeferred<DataResult<Attachment>>? = null
    private var retainedGrants = emptySet<String>()
    private val importStarted = CompletableDeferred<Unit>()
    private val importedProposals = mutableListOf<String>()
    private val system = object : SystemPort {
        override suspend fun fetchGatewayModels(edit: GatewayEdit): DataResult<GatewayCatalogResult> = DataResult.Failed("unused")
        override suspend fun checkGateway(profile: GatewayProfile): DataResult<GatewayCheckReport> = DataResult.Failed("unused")
        override suspend fun beginCapture(conversation: String, workspace: String): DataResult<CameraCapture> = DataResult.Failed("unused")
        override suspend fun capture(): DataResult<CameraCapture?> = DataResult.Loaded(null)
        override suspend fun finishCapture(id: String, success: Boolean): DataResult<CameraCapture?> = DataResult.Loaded(null)
        override suspend fun discardCapture(id: String): OperationResult = OperationResult.Done
        override suspend fun previewCapture(id: String): DataResult<AttachmentPreview> = DataResult.Failed("unused")
        override suspend fun retainAttachmentGrants(locations: Set<String>) { retainedGrants = locations }
        override suspend fun importAttachment(workspace: String, location: String): DataResult<Attachment> { importStarted.complete(Unit); return importGate?.await() ?: DataResult.Failed("unused") }
        override suspend fun previewAttachment(workspace: String, ref: String, expanded: Boolean): DataResult<AttachmentPreview> = DataResult.Failed("unused")
        override suspend fun attachment(workspace: String, ref: String): DataResult<Attachment> = DataResult.Failed("unused")
        override suspend fun skills(agent: DomainAgent) = DataResult.Loaded(emptyList<Skill>())
        override suspend fun readSkill(ref: String) = DataResult.Failed("unavailable")
        override suspend fun previewSkill(markdown: String) = DataResult.Failed("unavailable")
        override suspend fun previewManualSkill(agent: DomainAgent, name: String, description: String, body: String) = DataResult.Failed("unavailable")
        override suspend fun readSkillImport(location: String) = DataResult.Failed("unavailable")
        override suspend fun importSkill(agent: DomainAgent, markdown: String): DataResult<Skill> {
            importedProposals += markdown
            return DataResult.Loaded(Skill("saved", agent, "fixture", "fixture", "local", true, null))
        }
        override suspend fun saveManualSkill(agent: DomainAgent, name: String, description: String, body: String) = DataResult.Failed("unavailable")
        override val status = flowOf(SystemStatus(true, true, "ready"))
        override val diagnostic = flowOf(DiagnosticOutput(null, emptyList()))
        override suspend fun agents() = emptyList<AgentOption>()
        override suspend fun gateways() = listOf(GatewayProfile(DomainAgent.CODEX, "CODEX", 0, "", "test-model", "RESPONSES", false))
        override suspend fun saveGateway(edit: GatewayEdit) = GatewaySaveResult.Saved(emptyList(), null)
        override suspend fun defaultGateway() = GatewayDefault(DomainAgent.CODEX, "CODEX", 0)
        override suspend fun selectDefaultGateway(profile: GatewayProfile) = OperationResult.Done
        override suspend fun deleteGateway(id: String) = OperationResult.Done
        override suspend fun initialize() = OperationResult.Done
        override suspend fun shell(command: String) = OperationResult.Done
        override suspend fun stopShell() = OperationResult.Done
    }
    @Before fun setUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase("interaction-current.db")
        start()
    }
    private fun start() {
        db = InteractionDatabase.open(ApplicationProvider.getApplicationContext())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = RoomInteractionRepository(db, runtime, system, scope, RuntimeExecutionAdapter(runtime))
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private suspend fun state(predicate: (InteractionState) -> Boolean = { it.selected != null }): InteractionState = try {
        withTimeout(10_000) { repository.state.first(predicate) }
    } catch (e: TimeoutCancellationException) {
        val latest = repository.state.value
        val selected = latest.selected?.conversation
        val rows = selected?.let { db.dao().conversationTurns(it.id.value) }
        val persisted = selected?.let { db.dao().conversation(it.id.value)?.domain() }
        throw AssertionError("State never matched: selected=${selected?.id}, agent=${selected?.config?.agent}, workspace=${selected?.config?.workspace}, project=${selected?.project}, session=${selected?.session}, sessions=${selected?.sessions}, persistedSession=${persisted?.session}, persistedSessions=${persisted?.sessions}, turns=${rows?.map { it.id to Triple(storageJson.decodeFromString<StoredConversation>(it.frozen).agent, storageJson.decodeFromString<StoredConversation>(it.frozen).workspace, it.snapshot?.let { value -> storageJson.decodeFromString<RunSnapshot>(value).sessionRef?.value }) }}, error=${latest.error}", e)
    }
    @Test fun `first turn names an untouched conversation after a language change`() = runBlocking {
        val language = com.github.ytlog.mobby.android.localization.AppLanguage
        language.current = com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE
        try {
            val id = repository.create(NextTurnConfig(DomainAgent.CODEX, "test-model", null, "default", "CODEX"))
            language.current = com.github.ytlog.mobby.android.localization.AppLanguage.ENGLISH
            repository.editDraft(id, "Review my changes", 17, 17)
            assertTrue(repository.prepareTurn(id, TurnId("language-turn")) is PrepareTurnResult.Prepared)
            assertEquals("Review my changes", db.dao().conversation(id.value)!!.domain().title)
        } finally { language.current = com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE }
    }

    @Test fun `initial page loads forty turns and older pages preserve range when a new reply arrives`() = runBlocking {
        val c = seedHistory(110)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val initial = state { it.selected?.turns?.size == 40 }.selected!!
        assertEquals("turn-070", initial.turns.first().id.value); assertTrue(initial.hasEarlier)
        repository.loadEarlier(c.id)
        val expanded = state { it.selected?.turns?.size == 80 }.selected!!
        assertEquals("turn-030", expanded.turns.first().id.value)
        val frozen = db.dao().conversation(c.id.value)!!.body
        db.dao().save(TurnRow("turn-110", c.id.value, "new", frozen, 110, pending = false, occupied = false))
        val appended = state { it.selected?.turns?.size == 81 }.selected!!
        assertEquals("turn-030", appended.turns.first().id.value)
        assertEquals("turn-110", appended.turns.last().id.value)
        repository.loadEarlier(c.id)
        assertFalse(state { it.selected?.turns?.size == 111 }.selected!!.hasEarlier)
    }
    @Test fun `full history includes unloaded messages and reveal and saved anchor restore their range`() = runBlocking {
        val c = seedHistory(90)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        state { it.selected?.turns?.size == 40 }
        val full = repository.history(c.id)
        assertEquals(90, full.turns.size)
        assertEquals("turn-005", ConversationSearch.find(full, "old assistant body").single().turnId.value)
        val hit = ConversationSearch.find(full, "message-005").single()
        repository.revealTurn(c.id, hit.turnId)
        state { it.selected?.turns?.firstOrNull()?.id == hit.turnId }
        repository.anchor(c.id, hit.targetKey, 17)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.firstOrNull()?.id == hit.turnId }.selected!!
        assertEquals(hit.targetKey, restored.conversation.anchor)
        assertEquals(17, restored.conversation.anchorOffset)
    }
    private suspend fun seedHistory(count: Int): Conversation {
        val c = state().selected!!.conversation
        val frozen = db.dao().conversation(c.id.value)!!.body
        db.withTransaction {
            repeat(count) { n ->
                val number = n.toString().padStart(3, '0')
                val row = TurnRow("turn-$number", c.id.value, "message-$number", frozen, n.toLong(), pending = false, occupied = false)
                if (n == 5) {
                    val config = RunConfigSnapshot(RuntimeAgent.CODEX, WorkspaceRef("default"), "test-model", null, GatewayProfileRef("CODEX", 0), emptySet())
                    val snapshot = RunSnapshot(RunId("history-run"), RunPhase.FAILED, 1, 1, config,
                        outputSegments = listOf(OutputSegment("assistant", 0, ResourceRef("history-body"))))
                    db.dao().save(row.copy(runId = "history-run", snapshot = storageJson.encodeToString(snapshot)))
                    db.dao().chunks(listOf(ChunkRow("history-body", "history-run", "old assistant body")))
                } else db.dao().save(row)
            }
        }
        return c
    }
    @Test fun `older occupied run remains visible after paginated conversation reopens`() = runBlocking {
        val c = seedHistory(90)
        val row = db.dao().turn("turn-003")!!
        runtime.admit(row.execution())
        db.dao().save(row.copy(runId = row.id, snapshot = storageJson.encodeToString(runtime.snapshots.getValue(row.id)), occupied = true))
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.any { t -> t.id.value == row.id && t.occupied } == true }.selected!!
        assertEquals(c.id, restored.conversation.id)
        assertEquals("turn-003", restored.turns.first().id.value)
    }
    @Test fun `sidebar selects latest tied turn but keeps earlier occupied state without loading another timeline`() = runBlocking {
        val c = state().selected!!.conversation
        val other = repository.create(c.config)
        val frozen = db.dao().conversation(c.id.value)!!.body
        val otherFrozen = db.dao().conversation(other.value)!!.body
        val config = RunConfigSnapshot(RuntimeAgent.CODEX, WorkspaceRef("default"), "test-model", null, GatewayProfileRef("CODEX", 0), emptySet())
        val older = storageJson.encodeToString(RunSnapshot(RunId("a"), RunPhase.RUNNING, 1, 1, config))
        val latest = storageJson.encodeToString(RunSnapshot(RunId("z"), RunPhase.FAILED, 1, 1, config))
        db.dao().save(TurnRow("a", c.id.value, "older", frozen, 100, runId = "a", snapshot = older, pending = false, occupied = true))
        db.dao().save(TurnRow("z", c.id.value, "latest", frozen, 100, snapshot = latest, pending = false, occupied = false))
        db.dao().save(TurnRow("other", other.value, "separate", otherFrozen, 101, pending = false, occupied = false))
        val activities = db.dao().conversationActivities().first()
        assertEquals(latest, activities.single { it.conversationId == c.id.value }.snapshot)
        assertTrue(activities.single { it.conversationId == c.id.value }.occupied)
        assertEquals("a", activities.single { it.conversationId == c.id.value }.executionId)
        assertFalse(activities.single { it.conversationId == other.value }.occupied)
        assertNull(activities.single { it.conversationId == other.value }.executionId)
        assertEquals(listOf("a", "z"), db.dao().timeline(c.id.value, 40).first().map { it.turn.id })
        repository.select(other)
        val selected = state { it.selected?.conversation?.id == other && it.selected!!.turns.size == 1 }
        assertEquals("separate", selected.selected!!.turns.single().userText)
        assertEquals(c.id, selected.occupied!!.conversation.id)
        assertEquals(ExecutionPhase.FAILED, selected.occupied!!.phase)
        assertEquals(ExecutionId("a"), selected.occupied!!.execution)
    }
    @Test fun `source retention follows pending imports across conversations and releases only completed or discarded ones`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "keep original", 2, 4)
        val other = repository.create(c.config)
        val first = PendingAttachment("camera-1", c.config.workspace, "content://fixture/camera-1")
        val second = PendingAttachment("camera-2", c.config.workspace, "content://fixture/camera-2")
        repository.beginAttachment(c.id, first); repository.beginAttachment(other, second)
        repository.finishAttachment(c.id, first.id, DataResult.Failed("import failed"))
        assertEquals(setOf(first.location, second.location), retainedGrants)
        repository.discardAttachment(c.id, first.id)
        assertEquals(setOf(second.location), retainedGrants)
        repository.finishAttachment(other, second.id, DataResult.Loaded(Attachment("image:camera", "photo.jpg", 100, "image/jpeg")))
        assertTrue(retainedGrants.isEmpty())
        assertEquals("keep original", db.dao().conversation(c.id.value)!!.domain().draft.text)
        assertEquals(listOf("image:camera"), db.dao().conversation(other.value)!!.domain().draft.attachments)
    }
    @Test fun `send cannot freeze an incomplete attachment import`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "review", 6, 6)
        val gate = CompletableDeferred<DataResult<Attachment>>(); importGate = gate
        val actions = InteractionUseCases(repository, RuntimeExecutionAdapter(runtime), system, { UUID.randomUUID().toString() }, scope, InteractionPreferences(ApplicationProvider.getApplicationContext()))
        val job = async { actions.importAttachment(c.id, c.config.workspace, "content://fixture/document") }
        try {
            withTimeout(5_000) { importStarted.await() }
            assertTrue(repository.prepareTurn(c.id, TurnId("too-early")) is PrepareTurnResult.Rejected)
        } finally { gate.complete(DataResult.Failed("fixture failure")); job.await() }
    }
    @Test fun `interrupted import reopens with original draft and late completion cannot replace a retry`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "original", 3, 5)
        val pending = PendingAttachment("first", c.config.workspace, "content://fixture/document")
        repository.beginAttachment(c.id, pending)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.conversation?.draft?.pendingAttachment?.error != null }.selected!!.conversation
        assertEquals("original", restored.draft.text)
        assertEquals(3, restored.draft.selectionStart); assertEquals(5, restored.draft.selectionEnd)
        assertEquals(pending.location, restored.draft.pendingAttachment!!.location)
        assertTrue(repository.prepareTurn(c.id, TurnId("blocked")) is PrepareTurnResult.Rejected)
        repository.beginAttachment(c.id, pending.copy(id = "retry"))
        assertEquals(setOf(pending.location), retainedGrants)
        repository.finishAttachment(c.id, "first", DataResult.Loaded(Attachment("text:old", "old", 1)))
        assertTrue(db.dao().conversation(c.id.value)!!.domain().draft.attachments.isEmpty())
        repository.finishAttachment(c.id, "retry", DataResult.Loaded(Attachment("text:new", "new", 1)))
        val finished = db.dao().conversation(c.id.value)!!.domain().draft
        assertNull(finished.pendingAttachment); assertEquals(listOf("text:new"), finished.attachments)
    }
    @Test fun `losing import caller does not lose result and discarding prevents late attachment`() = runBlocking {
        val c = state().selected!!.conversation
        val gate = CompletableDeferred<DataResult<Attachment>>(); importGate = gate
        val actions = InteractionUseCases(repository, RuntimeExecutionAdapter(runtime), system, { UUID.randomUUID().toString() }, scope, InteractionPreferences(ApplicationProvider.getApplicationContext()))
        val caller = launch { actions.importAttachment(c.id, c.config.workspace, "content://fixture/document") }
        withTimeout(5_000) { importStarted.await() }; caller.cancelAndJoin()
        gate.complete(DataResult.Loaded(Attachment("text:survived", "file", 2)))
        state { it.selected?.conversation?.draft?.attachments == listOf("text:survived") }
        repository.beginAttachment(c.id, PendingAttachment("discard", c.config.workspace, "content://fixture/other"))
        repository.discardAttachment(c.id, "discard")
        repository.finishAttachment(c.id, "discard", DataResult.Loaded(Attachment("text:late", "late", 3)))
        assertEquals(listOf("text:survived"), db.dao().conversation(c.id.value)!!.domain().draft.attachments)
    }
    @Test fun `attachment only turn freezes references and new draft edits survive acceptance and restart`() = runBlocking {
        val c = state().selected!!.conversation
        repository.setAttachment(c.id, "text:original", true)
        val turn = (repository.prepareTurn(c.id, TurnId("attachment")) as PrepareTurnResult.Prepared).turn
        assertEquals(listOf(InputPart.Text(""), InputPart.Resource(ResourceRef("text:original"))), turn.request().inputParts)
        repository.setAttachment(c.id, "text:original", false)
        repository.setAttachment(c.id, "text:next", true)
        runtime.admit(turn)
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("attachment")))
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.singleOrNull()?.execution != null }.selected!!
        assertEquals(listOf("text:original"), restored.turns.single().attachments)
        assertEquals(listOf("text:next"), restored.conversation.draft.attachments)
        repository.restoreDraft(c.id, "retry", restored.turns.single().attachments)
        val draft = db.dao().conversation(c.id.value)!!.domain().draft
        assertEquals("retry", draft.text)
        assertEquals(listOf("text:original"), draft.attachments)
    }
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
    @Test fun `stopping refreshes terminal runtime state without waiting for the event observer`() = runBlocking {
        val conversation = state().selected!!.conversation
        repository.editDraft(conversation.id, "stop me", 7, 7)
        val turn = (repository.prepareTurn(conversation.id, TurnId("stopped")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("stopped")))
        state { it.selected?.turns?.singleOrNull()?.occupied == true }
        runtime.snapshots["stopped"] = runtime.snapshots.getValue("stopped").copy(
            phase = RunPhase.CANCELLED, lastSequence = 20, terminalEvidence = TerminalEvidence(true, null))

        repository.refreshExecution(ExecutionId("stopped"))

        val stopped = state { it.selected?.turns?.singleOrNull()?.phase == ExecutionPhase.CANCELLED }
        assertFalse(stopped.selected!!.turns.single().occupied)
        assertEquals(20L, db.dao().turn("stopped")!!.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it).lastSequence })
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
        assertEquals(c.id, new)
        val created = db.dao().conversation(new.value)!!.domain()
        assertEquals("draft A", created.draft.text)
        assertNull(created.session)
        assertEquals(1, repository.history(c.id).turns.size)
        assertEquals("draft B", db.dao().conversation(second.value)!!.domain().draft.text)
    }
    @Test fun `follow-up reuses the engine session and switching agents restores each one`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "remember this", 13, 13)
        val first = (repository.prepareTurn(c.id, TurnId("codex-turn")) as PrepareTurnResult.Prepared).turn
        assertNull(first.session)
        publish(first, "codex-session")
        repository.recordSubmission(first, Submission.Accepted(ExecutionId("codex-turn")))
        val resumed = state { it.selected?.conversation?.session == "codex-session" && it.selected?.turns?.singleOrNull()?.occupied == false }
        assertFalse(resumed.selected!!.turns.single().occupied)
        repository.editDraft(c.id, "same engine", 11, 11)
        val second = (repository.prepareTurn(c.id, TurnId("codex-again")) as PrepareTurnResult.Prepared).turn
        assertEquals("codex-session", second.session)
        repository.recordSubmission(second, Submission.Rejected(Failure.BUSY))
        val switched = repository.configure(c.id, c.config.copy(agent = DomainAgent.CLAUDE_CODE, gatewayProfile = "CLAUDE"))
        assertEquals(c.id, switched)
        repository.editDraft(switched, "other engine", 12, 12)
        val claude = (repository.prepareTurn(switched, TurnId("claude-turn")) as PrepareTurnResult.Prepared).turn
        assertNull(claude.session)
        assertEquals(DomainAgent.CLAUDE_CODE, claude.config.agent)
        publish(claude, "claude-session")
        repository.recordSubmission(claude, Submission.Accepted(ExecutionId("claude-turn")))
        state { it.selected?.conversation?.session == "claude-session" }
        val back = repository.configure(switched, c.config)
        assertEquals(c.id, back)
        assertEquals("codex-session", repository.conversation(back).session)
        repository.editDraft(back, "back again", 10, 10)
        val restored = (repository.prepareTurn(back, TurnId("codex-restored")) as PrepareTurnResult.Prepared).turn
        assertEquals("codex-session", restored.session)
        assertEquals("remember this", repository.history(c.id).turns.first().userText)
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
    @Test fun `skill choice is draft scoped and creator binding can be removed`() = runBlocking {
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
        val firstCreator = (repository.prepareTurn(created, TurnId("creator-first")) as PrepareTurnResult.Prepared).turn
        repository.recordSubmission(firstCreator, Submission.Rejected(Failure.BUSY))
        repository.setSkill(created, "skill:CODEX:BUILTIN:skill-creator:hash", false)
        repository.editDraft(created, "补充需求", 4, 4)
        val followUp = (repository.prepareTurn(created, TurnId("creator-followup")) as PrepareTurnResult.Prepared).turn
        assertTrue(followUp.draft.capabilities.isEmpty())
    }
    @Test fun `plugin capability can join a draft for either agent and survives prepare`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "use the phone", 13, 13)
        repository.setSkill(c.id, "plugin:device:screen", true)
        assertEquals(setOf("plugin:device:screen"), repository.conversation(c.id).draft.capabilities)
        val switched = repository.configure(c.id, c.config.copy(agent = DomainAgent.CLAUDE_CODE, gatewayProfile = "CLAUDE_CODE"))
        assertEquals(setOf("plugin:device:screen"), repository.conversation(switched).draft.capabilities)
        val prepared = (repository.prepareTurn(switched, TurnId("phone-run")) as PrepareTurnResult.Prepared).turn
        assertEquals(setOf("plugin:device:screen"), prepared.draft.capabilities)
        assertEquals(DomainAgent.CLAUDE_CODE, prepared.config.agent)
        repository.setSkill(switched, "plugin:device:screen", false)
        assertTrue(repository.conversation(switched).draft.capabilities.isEmpty())
        assertEquals(setOf("plugin:device:screen"), prepared.draft.capabilities)
        var rejected = false
        try {
            repository.setSkill(c.id, "plugin:PHONE:ACCESSIBILITY", true)
        } catch (error: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        val stored = StoredConversation("id", "CODEX", "model", null, "workspace", "CODEX", 0,
            capabilities = setOf("plugin:PHONE:ACCESSIBILITY", "plugin:device:screen", "skill:CODEX:USER:review:hash"))
        assertEquals(setOf("plugin:device:screen", "skill:CODEX:USER:review:hash"), stored.domain().draft.capabilities)
    }
    @Test fun `unexpected missing output remains a synchronization error rather than retention success`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "fixture", 7, 7)
        val turn = (repository.prepareTurn(c.id, TurnId("missing")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val ref = ResourceRef("missing/0")
        runtime.snapshots["missing"] = runtime.snapshots.getValue("missing").copy(phase = RunPhase.SUCCEEDED,
            outputSegments = listOf(OutputSegment("answer", 0, ref)), terminalEvidence = TerminalEvidence(true, 0))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("missing")))
        val projected = state { it.selected?.turns?.singleOrNull()?.failure != null }.selected!!.turns.single()
        assertTrue(projected.failure!!.contains("同步中断"))
        assertTrue(projected.messages.isEmpty())
        assertNull(db.dao().chunk(ref.value))
    }
    @Test fun `terminal cache byte budget expires oldest whole run while preserving active output and user data`() = runBlocking {
        val c = seedHistory(7)
        repository.editDraft(c.id, "keep draft", 10, 10)
        val conversation = db.dao().conversation(c.id.value)!!
        val old = db.dao().turn("turn-005")!!
        val snapshot = storageJson.decodeFromString<RunSnapshot>(old.snapshot!!)
        db.dao().chunks(listOf(ChunkRow("history-body", "history-run", "中")))
        for ((name, phase) in listOf("new" to RunPhase.SUCCEEDED, "active" to RunPhase.AWAITING_APPROVAL, "unknown" to RunPhase.OUTCOME_UNKNOWN)) {
            val state = snapshot.copy(runId = RunId(name), phase = phase, outputSegments = listOf(OutputSegment("answer", 0, ResourceRef("$name/0"))))
            db.dao().save(old.copy(id = name, runId = name, createdAt = 10, snapshot = storageJson.encodeToString(state), occupied = name == "active"))
            db.dao().chunks(listOf(ChunkRow("$name/0", name, "ab")))
        }
        OutputCache(db, runtime, 2).compact()
        assertEquals(ChunkRow("history-body", "history-run", "", true), db.dao().chunk("history-body"))
        for (name in listOf("new", "active", "unknown")) assertEquals("ab", db.dao().chunk("$name/0")!!.text)
        assertEquals(old, db.dao().turn(old.id))
        assertEquals(conversation, db.dao().conversation(c.id.value))
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        assertTrue(db.dao().outputCacheExpired("history-run"))
        assertEquals("", db.dao().chunk("history-body")!!.text)
    }
    @Test fun `cache cleanup traverses multiple pages without skipping rows removed from earlier pages`() = runBlocking {
        val c = seedHistory(7)
        val old = db.dao().turn("turn-005")!!
        val snapshot = storageJson.decodeFromString<RunSnapshot>(old.snapshot!!)
        db.withTransaction {
            db.dao().chunks(listOf(ChunkRow("history-body", "history-run", "x")))
            repeat(140) { n ->
                val name = "cache-" + n.toString().padStart(3, '0')
                db.dao().save(old.copy(id = name, runId = name, createdAt = 10, snapshot = storageJson.encodeToString(snapshot.copy(runId = RunId(name)))))
                db.dao().chunks(listOf(ChunkRow("$name/0", name, "x")))
            }
        }
        OutputCache(db, runtime, 1).compact()
        assertTrue(db.dao().outputCacheExpired("history-run"))
        repeat(139) { assertTrue(db.dao().outputCacheExpired("cache-" + it.toString().padStart(3, '0'))) }
        assertEquals("x", db.dao().chunk("cache-139/0")!!.text)
        assertEquals("message-005", db.dao().turn(old.id)!!.userText)
        assertEquals(c.id.value, db.dao().turn(old.id)!!.conversationId)
    }
    @Test fun `delayed projection cannot resurrect a run whose cache expired during download`() = runBlocking {
        val c = seedHistory(7)
        val old = db.dao().turn("turn-005")!!
        val ref = ResourceRef("history-run/late")
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        runtime.artifactGate = { request -> if (request.artifactRef == ref) { started.complete(Unit); release.await() } }
        runtime.artifactBodies[ref] = "late proposal".toByteArray()
        runtime.snapshots["history-run"] = storageJson.decodeFromString<RunSnapshot>(old.snapshot!!).copy(lastSequence = 2, revision = 2, artifacts = listOf(ref))
        repository.recordSubmission(old.execution(), Submission.Accepted(ExecutionId("history-run")))
        withTimeout(5000) { started.await() }
        OutputCache(db, runtime, 0).compact()
        release.complete(Unit)
        val projected = state { it.selected?.turns?.singleOrNull { t -> t.id.value == old.id }?.let { t -> !t.proposalsLoading && t.messages.any { it.text == "技能草稿已按保留策略清理" } } == true }
        assertTrue(projected.selected!!.turns.single { it.id.value == old.id }.skillProposals.isEmpty())
        assertEquals(ChunkRow(ref.value, "history-run", "", true), db.dao().chunk(ref.value))
        assertEquals("message-005", repository.history(c.id).turns.single { it.id.value == old.id }.userText)
    }
    @Test fun `unavailable original output preserves cached text and is not treated as expiration`() = runBlocking {
        val c = seedHistory(7)
        val turn = repository.history(c.id).turns.single { it.id.value == "turn-005" }
        assertEquals("old assistant body", turn.messages.single().text)
        assertFalse(db.dao().chunk("history-body")!!.expired)
        assertTrue(turn.failure!!.contains("已保留缓存"))
        runtime.artifactBodies[ResourceRef("history-body")] = "old assistant body".toByteArray()
        assertNull(repository.history(c.id).turns.single { it.id.value == "turn-005" }.failure)
        runtime.artifactBodies.clear()
        assertNotNull(repository.history(c.id).turns.single { it.id.value == "turn-005" }.failure)
        runtime.expireAt[ResourceRef("history-body")] = 0
        assertNull(repository.history(c.id).turns.single { it.id.value == "turn-005" }.failure)
        assertNull(repository.history(c.id).turns.single { it.id.value == "turn-005" }.failure)
    }
    @Test fun `already cached terminal output learns expiration without another runtime event`() = runBlocking {
        val c = seedHistory(7)
        runtime.expireAt[ResourceRef("history-body")] = 0L
        val history = repository.history(c.id)
        assertEquals("输出已按保留策略清理", history.turns.single { it.id.value == "turn-005" }.messages.single().text)
        assertEquals("", db.dao().chunk("history-body")!!.text)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        assertEquals("输出已按保留策略清理", repository.history(c.id).turns.single { it.id.value == "turn-005" }.messages.single().text)
        assertEquals("message-005", db.dao().turn("turn-005")!!.userText)
    }
    @Test fun `expired output discards partial bytes and expired proposals never become installable`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "fixture", 7, 7)
        val turn = (repository.prepareTurn(c.id, TurnId("expired")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val body = ResourceRef("expired/0"); val proposal = ResourceRef("expired/1")
        runtime.artifactBodies[body] = "partial text must not survive expiry".toByteArray()
        runtime.expireAt[body] = 17L; runtime.expireAt[proposal] = 0L
        runtime.snapshots["expired"] = runtime.snapshots.getValue("expired").copy(phase = RunPhase.SUCCEEDED,
            outputSegments = listOf(OutputSegment("answer", 0, body)), artifacts = listOf(proposal), terminalEvidence = TerminalEvidence(true, 0))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("expired")))
        val projected = state { it.selected?.turns?.singleOrNull()?.let { t -> t.failure != null || t.phase == ExecutionPhase.SUCCEEDED } == true }.selected!!.turns.single()
        assertNull(projected.failure)
        assertEquals(ExecutionPhase.SUCCEEDED, projected.phase)
        assertFalse(projected.occupied)
        assertEquals("输出已按保留策略清理", projected.messages.first().text)
        assertTrue(projected.messages.any { it.text == "技能草稿已按保留策略清理" })
        assertTrue(projected.skillProposals.isEmpty())
        assertFalse(projected.proposalsLoading)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.singleOrNull()?.phase == ExecutionPhase.SUCCEEDED }.selected!!.turns.single()
        assertEquals(projected.messages, restored.messages)
        assertTrue(restored.skillProposals.isEmpty())
    }
    @Test fun `proposal text and artifact reference survive projection and database reopen`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "create", 6, 6)
        val turn = (repository.prepareTurn(c.id, TurnId("proposal")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val ref = ResourceRef("proposal/1")
        val body = "---\nname: test\ndescription: 中文说明\n---\n正文步骤\n"
        runtime.artifactBodies[ref] = body.toByteArray()
        runtime.snapshots["proposal"] = runtime.snapshots.getValue("proposal").copy(phase = RunPhase.SUCCEEDED,
            artifacts = listOf(ref), terminalEvidence = TerminalEvidence(true, 0, terminationConfirmed = true))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId("proposal")))
        val projected = state { it.selected?.turns?.singleOrNull()?.skillProposals?.isNotEmpty() == true }
        assertEquals(body, projected.selected!!.turns.single().skillProposals.single().markdown)
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.singleOrNull()?.skillProposals?.isNotEmpty() == true }
        assertEquals(ref.value, restored.selected!!.turns.single().skillProposals.single().ref)
        assertEquals(body, restored.selected!!.turns.single().skillProposals.single().markdown)
    }
    @Test fun `generated skill save rechecks source and rejects runtime or local expiration`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "create", 6, 6)
        val turn = (repository.prepareTurn(c.id, TurnId("save-proposal")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val ref = ResourceRef("save-proposal/0")
        runtime.artifactBodies[ref] = "original".toByteArray()
        runtime.snapshots[turn.turnId.value] = runtime.snapshots.getValue(turn.turnId.value).copy(phase = RunPhase.SUCCEEDED,
            artifacts = listOf(ref), terminalEvidence = TerminalEvidence(true, 0))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId(turn.turnId.value)))
        val proposal = state { it.selected?.turns?.singleOrNull()?.skillProposals?.isNotEmpty() == true }.selected!!.turns.single().skillProposals.single()
        assertTrue(repository.saveSkillProposal(proposal, "user edit") is DataResult.Loaded)
        assertEquals(listOf("user edit"), importedProposals)
        runtime.artifactBodies.clear()
        assertNotNull(repository.history(c.id).turns.single().failure)
        runtime.expireAt[ref] = 0
        assertTrue(repository.saveSkillProposal(proposal, "second edit") is DataResult.Failed)
        assertTrue(db.dao().chunk(ref.value)!!.expired)
        assertNull(repository.history(c.id).turns.single().failure)
        runtime.expireAt.clear()
        assertTrue(repository.saveSkillProposal(proposal, "third edit") is DataResult.Failed)
        assertEquals(listOf("user edit"), importedProposals)
    }
    @Test fun `projected replies keep chunk order and adjacent tools form one run`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "phone", 5, 5)
        val turn = (repository.prepareTurn(c.id, TurnId("ordered")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val first = ResourceRef("ordered/first")
        val second = ResourceRef("ordered/second")
        val shot = ResourceRef("ordered/shot")
        val tap = ResourceRef("ordered/tap")
        runtime.artifactBodies[first] = "先看屏幕".toByteArray()
        runtime.artifactBodies[second] = "再打开商店".toByteArray()
        runtime.artifactBodies[shot] = "screen".toByteArray()
        runtime.artifactBodies[tap] = "clicked".toByteArray()
        runtime.snapshots[turn.turnId.value] = runtime.snapshots.getValue(turn.turnId.value).copy(phase = RunPhase.RUNNING,
            outputSegments = listOf(OutputSegment("first", 0, first), OutputSegment("second", 3, second)),
            steps = listOf(
                ToolSnapshot("s1", StepBody.Action("snapshot", ""), ToolOutcome.SUCCEEDED, listOf(OutputSegment("tool:s1", 1, shot))),
                ToolSnapshot("s2", StepBody.Action("click", ""), ToolOutcome.SUCCEEDED, listOf(OutputSegment("tool:s2", 2, tap))),
                ToolSnapshot("s3", StepBody.Action("recents"))))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId(turn.turnId.value)))
        val projected = state { it.selected?.turns?.singleOrNull()?.steps?.size == 3 }.selected!!.turns.single().transcript()
        assertEquals("先看屏幕", (projected[0] as TranscriptEntry.Reply).message.text)
        assertEquals(listOf("s1", "s2"), (projected[1] as TranscriptEntry.ToolRun).steps.map { it.id })
        assertEquals("再打开商店", (projected[2] as TranscriptEntry.Reply).message.text)
        assertEquals(listOf("s3"), (projected[3] as TranscriptEntry.ToolRun).steps.map { it.id })
    }
    @Test fun `thinking deltas join without a newline between tokens`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "think", 5, 5)
        val turn = (repository.prepareTurn(c.id, TurnId("thought")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val first = ResourceRef("thought/0")
        val second = ResourceRef("thought/1")
        runtime.artifactBodies[first] = "先".toByteArray()
        runtime.artifactBodies[second] = "想一下".toByteArray()
        runtime.snapshots[turn.turnId.value] = runtime.snapshots.getValue(turn.turnId.value).copy(phase = RunPhase.RUNNING,
            steps = listOf(ToolSnapshot("think", StepBody.Thinking, null, listOf(
                OutputSegment("tool:think", 1, second),
                OutputSegment("tool:think", 0, first),
            ))))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId(turn.turnId.value)))
        val thinking = state { it.selected?.turns?.singleOrNull()?.steps?.isNotEmpty() == true }.selected!!.turns.single().steps.single()
        assertEquals("先想一下", (thinking as Step.Thinking).text)
    }
    @Test fun `permission projection survives database reopen and adapter preserves decision identity`() = runBlocking {
        val c = state().selected!!.conversation
        repository.editDraft(c.id, "write", 5, 5)
        val turn = (repository.prepareTurn(c.id, TurnId("permission-turn")) as PrepareTurnResult.Prepared).turn
        runtime.admit(turn)
        val pending = PendingApproval("native-id", 7, ApprovalSubject.FileWrite("/fixture/file", "literal"))
        runtime.snapshots[turn.turnId.value] = runtime.snapshots.getValue(turn.turnId.value).copy(phase = RunPhase.AWAITING_APPROVAL, revision = 7, lastSequence = 7, pendingApprovals = listOf(pending))
        repository.recordSubmission(turn, Submission.Accepted(ExecutionId(turn.turnId.value)))
        val projected = state { it.selected?.turns?.singleOrNull()?.permissions?.isNotEmpty() == true }.selected!!.turns.single()
        assertEquals(PermissionRequest(pending.approvalId, pending.revision, PermissionSubject.FileWrite("/fixture/file", "literal")), projected.permissions.single())
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); start()
        val restored = state { it.selected?.turns?.singleOrNull()?.permissions?.isNotEmpty() == true }.selected!!.turns.single()
        assertEquals(projected.permissions, restored.permissions)
        val adapter = RuntimeExecutionAdapter(runtime)
        val decision = PermissionDecision("command", PermissionKey(restored.execution!!, pending.approvalId, pending.revision), true)
        runtime.permissionResult = CommandResult.Accepted
        assertEquals(OperationResult.Done, adapter.resolvePermission(decision))
        assertEquals(OperationResult.Done, adapter.resolvePermission(decision))
        assertEquals(listOf(ApprovalDecision(CommandId("command"), RunId(turn.turnId.value), pending.approvalId, ApprovalChoice.ALLOW_ONCE, 7)), runtime.decisions.distinct())
        runtime.permissionResult = CommandResult.Rejected(RuntimeError(ErrorCode.STALE_APPROVAL))
        assertEquals(OperationResult.Failed("此确认请求已失效"), adapter.resolvePermission(decision.copy(commandId = "deny", allow = false)))
        assertEquals(ApprovalChoice.DENY, runtime.decisions.last().choice)
    }
    private fun publish(turn: TurnExecution, session: String) {
        // Publish one atomic baseline: observers must not capture an intermediate RUNNING snapshot
        // from a fake that never emits a subsequent update.
        runtime.snapshots[turn.turnId.value] = runtime.initialSnapshot(turn).copy(
            phase = RunPhase.SUCCEEDED, sessionRef = SessionRef(session), terminalEvidence = TerminalEvidence(true, 0))
    }
    private class TestRuntime : RuntimeClient {
        override val connection = MutableStateFlow(ConnectionState.CONNECTED)
        val snapshots = mutableMapOf<String, RunSnapshot>()
        var submissions = 0
        fun admit(turn: TurnExecution) { snapshots[turn.turnId.value] = initialSnapshot(turn) }
        fun initialSnapshot(turn: TurnExecution): RunSnapshot {
            val request = turn.request()
            return RunSnapshot(RunId(turn.turnId.value), RunPhase.RUNNING, 1, 1,
                RunConfigSnapshot(request.agentId, request.workspaceRef, request.modelId, request.reasoningLevel, request.gatewayProfileRef, request.capabilityRefs))
        }
        override suspend fun capabilities() = CapabilityResult.Available(RuntimeCapabilities("test", emptyList()))
        override suspend fun submit(request: RunRequest): SubmitResult { submissions++; return SubmitResult.Rejected(RuntimeError(ErrorCode.BUSY)) }
        override suspend fun findByRequest(requestId: RequestId): RequestLookup = if (requestId.value in snapshots) RequestLookup.Found(RunId(requestId.value)) else RequestLookup.NotFound
        override suspend fun cancel(request: CancelRequest) = CommandResult.Accepted
        val decisions = mutableListOf<ApprovalDecision>()
        var permissionResult: CommandResult = CommandResult.Rejected(RuntimeError(ErrorCode.UNSUPPORTED_CAPABILITY))
        override suspend fun resolveApproval(request: ApprovalDecision): CommandResult { decisions += request; return permissionResult }
        override suspend fun snapshot(runId: RunId): SnapshotResult = snapshots[runId.value]?.let { SnapshotResult.Found(it) } ?: SnapshotResult.Unavailable(RuntimeError(ErrorCode.NOT_FOUND))
        override fun observe(runId: RunId, after: EventCursor?): Flow<RuntimeUpdate> = flow {
            snapshots[runId.value]?.let { emit(RuntimeUpdate.Baseline(it, EventCursor(runId, it.lastSequence))) }
            awaitCancellation()
        }
        var artifactGate: suspend (ArtifactReadRequest) -> Unit = {}
        val expireAt = mutableMapOf<ResourceRef, Long>()
        val artifactBodies = mutableMapOf<ResourceRef, ByteArray>()
        override suspend fun readArtifact(request: ArtifactReadRequest): ArtifactReadResult {
            artifactGate(request)
            if (expireAt[request.artifactRef]?.let { request.offset >= it } == true) return ArtifactReadResult.Expired
            val bytes = artifactBodies[request.artifactRef] ?: return ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
            val end = minOf(bytes.size, request.offset.toInt() + 17)
            return ArtifactReadResult.Chunk(bytes.copyOfRange(request.offset.toInt(), end).toList(), end.toLong().takeIf { end < bytes.size }, false)
        }
    }
}
