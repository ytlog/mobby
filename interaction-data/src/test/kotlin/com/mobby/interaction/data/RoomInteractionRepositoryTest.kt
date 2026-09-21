package com.mobby.interaction.data

import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import com.mobby.interaction.domain.*
import com.mobby.interaction.domain.AgentId as DomainAgent
import com.mobby.runtime.api.*
import com.mobby.runtime.api.AgentId as RuntimeAgent
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
    private val system = object : SystemPort {
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
    @Test fun `version one database migrates with conversation messages and output intact`() = runBlocking {
        val c = seedHistory(7)
        val original = db.dao().conversation(c.id.value)!!.body
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP INDEX index_turns_conversationId_createdAt_id")
        sql.execSQL("DROP INDEX index_turns_conversationId_occupied")
        sql.execSQL("UPDATE room_master_table SET identity_hash='323a224c2892d320614221fcfb9d928c' WHERE id=42")
        sql.execSQL("PRAGMA user_version=1")
        db.close(); start()
        val restored = state { it.selected?.turns?.size == 7 }.selected!!
        assertEquals(original, db.dao().conversation(c.id.value)!!.body)
        assertEquals("old assistant body", restored.turns.single { it.id.value == "turn-005" }.messages.single().text)
        assertEquals(2, db.openHelper.readableDatabase.version)
        val indexes = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA index_list(turns)").use { cursor ->
            while (cursor.moveToNext()) indexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertTrue("index_turns_conversationId_createdAt_id" in indexes)
        assertTrue("index_turns_conversationId_occupied" in indexes)
    }
    @Test fun `sidebar selects latest tied turn but keeps earlier occupied state without loading another timeline`() = runBlocking {
        val c = state().selected!!.conversation
        val other = repository.create(c.config)
        val frozen = db.dao().conversation(c.id.value)!!.body
        val otherFrozen = db.dao().conversation(other.value)!!.body
        val config = RunConfigSnapshot(RuntimeAgent.CODEX, WorkspaceRef("default"), "test-model", null, GatewayProfileRef("CODEX", 0), emptySet())
        val older = storageJson.encodeToString(RunSnapshot(RunId("a"), RunPhase.RUNNING, 1, 1, config))
        val latest = storageJson.encodeToString(RunSnapshot(RunId("z"), RunPhase.FAILED, 1, 1, config))
        db.dao().save(TurnRow("a", c.id.value, "older", frozen, 100, snapshot = older, pending = false, occupied = true))
        db.dao().save(TurnRow("z", c.id.value, "latest", frozen, 100, snapshot = latest, pending = false, occupied = false))
        db.dao().save(TurnRow("other", other.value, "separate", otherFrozen, 101, pending = false, occupied = false))
        val activities = db.dao().conversationActivities().first()
        assertEquals(latest, activities.single { it.conversationId == c.id.value }.snapshot)
        assertTrue(activities.single { it.conversationId == c.id.value }.occupied)
        assertFalse(activities.single { it.conversationId == other.value }.occupied)
        assertEquals(listOf("a", "z"), db.dao().timeline(c.id.value, 40).first().map { it.turn.id })
        repository.select(other)
        val selected = state { it.selected?.conversation?.id == other && it.selected!!.turns.size == 1 }
        assertEquals("separate", selected.selected!!.turns.single().userText)
        assertEquals(c.id, selected.occupied!!.conversation.id)
        assertEquals(ExecutionPhase.FAILED, selected.occupied!!.phase)
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
        val firstCreator = (repository.prepareTurn(created, TurnId("creator-first")) as PrepareTurnResult.Prepared).turn
        repository.recordSubmission(firstCreator, Submission.Rejected(Failure.BUSY))
        repository.setSkill(created, "skill:CODEX:BUILTIN:skill-creator:hash", false)
        repository.editDraft(created, "补充需求", 4, 4)
        val followUp = (repository.prepareTurn(created, TurnId("creator-followup")) as PrepareTurnResult.Prepared).turn
        assertEquals(setOf("skill:CODEX:BUILTIN:skill-creator:hash"), followUp.draft.capabilities)
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
        val artifactBodies = mutableMapOf<ResourceRef, ByteArray>()
        override suspend fun readArtifact(request: ArtifactReadRequest): ArtifactReadResult {
            val bytes = artifactBodies[request.artifactRef] ?: return ArtifactReadResult.Unavailable(RuntimeError(ErrorCode.RESOURCE_MISSING))
            val end = minOf(bytes.size, request.offset.toInt() + 17)
            return ArtifactReadResult.Chunk(bytes.copyOfRange(request.offset.toInt(), end).toList(), end.toLong().takeIf { end < bytes.size }, false)
        }
    }
}
