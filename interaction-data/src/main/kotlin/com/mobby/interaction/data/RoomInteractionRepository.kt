package com.mobby.interaction.data

import androidx.room.withTransaction
import com.mobby.interaction.domain.*
import com.mobby.runtime.api.*
import com.mobby.interaction.domain.AgentId as DomainAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomInteractionRepository(
    private val db: InteractionDatabase, private val client: RuntimeClient, private val system: SystemPort,
    private val scope: CoroutineScope, private val execution: ExecutionPort,
    private val now: () -> Long = System::currentTimeMillis, private val id: () -> String = { UUID.randomUUID().toString() }
) : InteractionRepository {
    private val dao = db.dao()
    private val startupError = MutableStateFlow<String?>(null)
    private val observers = mutableMapOf<String, Job>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    override val state: StateFlow<InteractionState> = dao.selection().distinctUntilChanged().flatMapLatest { selected ->
        combine(dao.conversations(), dao.turns(), if (selected == null) flowOf(emptyList()) else dao.chunks(selected)) { rows, turns, chunks ->
            val conversations = rows.map { row ->
                val history = turns.filter { it.conversationId == row.id }
                val snapshot = history.lastOrNull()?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                ConversationSummary(row.domain(), snapshot?.let { RunProjection.verifiedPhase(it).domain() }, history.any { it.occupied })
            }
            val conversation = conversations.firstOrNull { it.conversation.id.value == selected }?.conversation
            val content = chunks.associate { it.ref to it.text }
            InteractionState(conversations, conversation?.let { c -> ConversationDetail(c, turns.filter { it.conversationId == selected }.map { it.domain(content) }) }, loading = false)
        }
    }.combine(startupError) { state, error -> state.copy(error = error ?: state.error) }.catch { emit(InteractionState(loading = false, error = "无法读取会话数据库；原数据已保留，请重启应用后重试")) }
        .stateIn(scope, SharingStarted.Eagerly, InteractionState())

    init {
        scope.launch {
            try {
                if (dao.allConversations().isEmpty()) {
                    val profile = runCatching { system.gateways().firstOrNull { it.agent == DomainAgent.CODEX } }.getOrNull()
                    create(NextTurnConfig(DomainAgent.CODEX, profile?.model.orEmpty(), null, "default", profile?.id ?: "CODEX", profile?.version ?: 0))
                } else if (dao.selection().first() == null) {
                    dao.allConversations().firstOrNull { !it.domain().deleted && !it.domain().archived }?.let { dao.select(SelectionRow(conversationId = it.id)) }
                }
                client.connection.filter { it == ConnectionState.CONNECTED }.collect {
                    for (turn in dao.unfinished()) {
                        if (turn.pending && turn.id !in inFlight) recordSubmission(turn.execution(), execution.lookup(TurnId(turn.id)))
                        else if (turn.runId != null) observe(turn.id, turn.runId)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { startupError.value = "会话恢复未完成，原数据已保留；请重启应用后重试" }
        }
    }
    override suspend fun setSkill(id: ConversationId, ref: String, enabled: Boolean) = mutate(id) { c ->
        require(!enabled || ref.startsWith("skill:${c.config.agent.name}:"))
        val refs = if (enabled) c.draft.capabilities + ref else c.draft.capabilities - ref
        require((refs + listOfNotNull(c.creator)).size <= 8)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, capabilities = refs))
    }
    override suspend fun createSkillConversation(id: ConversationId, creator: String): ConversationId = db.withTransaction {
        val old = requireNotNull(dao.conversation(id.value)).domain()
        require(creator.startsWith("skill:${old.config.agent.name}:"))
        val created = requireNotNull(ConversationRules.createSkillConversation(old, ConversationId(this.id()), creator)).copy(title = "创建技能", updatedAt = now())
        dao.save(created.row()); dao.select(SelectionRow(conversationId = created.id.value)); created.id
    }
    override suspend fun select(id: ConversationId) {
        val c = dao.conversation(id.value)?.domain() ?: return
        if (!c.deleted) dao.select(SelectionRow(conversationId = id.value))
    }
    override suspend fun create(config: NextTurnConfig): ConversationId {
        val c = Conversation(ConversationId(id()), config, updatedAt = now())
        db.withTransaction { dao.save(c.row()); dao.select(SelectionRow(conversationId = c.id.value)) }
        return c.id
    }
    override suspend fun editDraft(id: ConversationId, text: String, selectionStart: Int, selectionEnd: Int): Draft = db.withTransaction {
        val c = requireNotNull(dao.conversation(id.value)).domain()
        val draft = if (c.draft.text == text && c.draft.selectionStart == selectionStart && c.draft.selectionEnd == selectionEnd) c.draft
            else c.draft.copy(revision = c.draft.revision + 1, text = text, selectionStart = selectionStart.coerceIn(0, text.length), selectionEnd = selectionEnd.coerceIn(0, text.length))
        dao.save(c.copy(draft = draft).row()); draft
    }
    override suspend fun configure(id: ConversationId, config: NextTurnConfig): ConversationId = db.withTransaction {
        val old = requireNotNull(dao.conversation(id.value)).domain()
        val changed = ConversationRules.applyConfig(old, config, ConversationId(this.id())).copy(updatedAt = now())
        dao.save(changed.row()); dao.select(SelectionRow(conversationId = changed.id.value)); changed.id
    }
    override suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult = db.withTransaction {
        val c = dao.conversation(conversationId.value)?.domain() ?: return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (c.archived || c.deleted) return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (dao.conversationTurns(c.id.value).any { it.pending }) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
        if (c.draft.text.isBlank() && c.draft.attachments.isEmpty()) return@withTransaction PrepareTurnResult.Rejected(Failure.EMPTY_DRAFT)
        if (dao.turn(turnId.value) != null) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
        val frozen = c.copy(draft = c.draft.copy(capabilities = c.draft.capabilities + listOfNotNull(c.creator)))
        dao.save(TurnRow(turnId.value, c.id.value, c.draft.text, storageJson.encodeToString(StoredConversation.from(frozen)), now()))
        dao.save(c.copy(hasTurns = true, updatedAt = now(), title = if (!c.hasTurns && c.title == "新对话") c.draft.text.lineSequence().first().take(40).ifBlank { "新对话" } else c.title).row())
        inFlight.add(turnId.value)
        PrepareTurnResult.Prepared(TurnExecution(turnId, c.id, frozen.draft, c.config, c.session, c.creator != null))
    }
    override suspend fun recordSubmission(turn: TurnExecution, result: Submission) {
        db.withTransaction {
            val row = dao.turn(turn.turnId.value) ?: return@withTransaction
            if (!row.pending) return@withTransaction // Replayed acknowledgements must not clear a newer draft.
            when (result) {
                is Submission.Accepted -> {
                    dao.save(row.copy(runId = result.executionId.value, pending = false, occupied = true, error = null))
                    val c = requireNotNull(dao.conversation(turn.conversationId.value)).domain()
                    dao.save(c.copy(draft = ConversationRules.afterSubmission(c.draft, turn.draft.revision, result)).row())
                }
                is Submission.Rejected -> dao.save(row.copy(pending = false, occupied = false, error = when (result.reason) {
                    Failure.BUSY -> "请求未接纳：已有任务占用运行环境；草稿已保留"
                    Failure.INVALID_CONFIG -> "请求未接纳：请检查网关、模型或权限；草稿已保留"
                    Failure.UNSUPPORTED_CAPABILITY -> "请求未接纳：当前能力不可用；草稿已保留"
                    else -> "请求未接纳，草稿已保留，可检查连接后重试"
                }))
                Submission.Unconfirmed -> dao.save(row.copy(error = "请求结果尚未确认；请查询原请求，不要重复发送"))
            }
        }
        inFlight.remove(turn.turnId.value)
        if (result is Submission.Accepted) observe(turn.turnId.value, result.executionId.value)
    }
    override suspend fun pendingTurn(conversationId: ConversationId): TurnExecution? = dao.conversationTurns(conversationId.value).firstOrNull { it.pending }?.execution()
    override suspend fun updateGateway(profile: GatewayProfile) = db.withTransaction {
        for (row in dao.allConversations()) {
            val c = row.domain()
            if (c.config.agent == profile.agent && c.config.gatewayProfile == profile.id) {
                dao.save(c.copy(config = c.config.copy(model = profile.model, gatewayVersion = profile.version, reasoning = null)).row())
            }
        }
    }
    override suspend fun rename(id: ConversationId, title: String): OperationResult { mutate(id) { it.copy(title = title) }; return OperationResult.Done }
    override suspend fun pin(id: ConversationId) = mutate(id) { it.copy(pinned = !it.pinned) }
    override suspend fun setProject(id: ConversationId, project: String?) = mutate(id) { it.copy(project = project) }
    override suspend fun archive(id: ConversationId, archived: Boolean) = changeVisibility(id) { it.copy(archived = archived) }
    override suspend fun delete(id: ConversationId, deleted: Boolean) = changeVisibility(id) { it.copy(deleted = deleted) }
    private suspend fun changeVisibility(id: ConversationId, transform: (Conversation) -> Conversation): OperationResult = db.withTransaction {
        if (dao.conversationTurns(id.value).any { it.occupied }) return@withTransaction OperationResult.Failed("运行中的会话不能归档或删除")
        val c = dao.conversation(id.value)?.domain() ?: return@withTransaction OperationResult.Failed("会话不存在")
        dao.save(transform(c).row())
        OperationResult.Done
    }
    override suspend fun expansion(turnId: TurnId, expanded: Boolean) = db.withTransaction { dao.turn(turnId.value)?.let { dao.save(it.copy(expanded = expanded)) }; Unit }
    override suspend fun stepExpansion(turnId: TurnId, stepId: String, expanded: Boolean) = db.withTransaction {
        dao.turn(turnId.value)?.let {
            val old = storageJson.decodeFromString<Set<String>>(it.expandedSteps)
            dao.save(it.copy(expandedSteps = storageJson.encodeToString(if (expanded) old + stepId else old - stepId)))
        }; Unit
    }
    override suspend fun anchor(id: ConversationId, messageId: String?, offset: Int) = mutate(id) { it.copy(anchor = messageId, anchorOffset = offset) }
    private suspend fun mutate(id: ConversationId, transform: (Conversation) -> Conversation) = db.withTransaction {
        dao.conversation(id.value)?.let { dao.save(transform(it.domain()).row()) }; Unit
    }
    private fun observe(turnId: String, runId: String) {
        synchronized(observers) {
            if (observers[runId]?.isActive == true) return
            observers[runId] = scope.launch {
                try {
                    val prior = dao.turn(turnId)?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                    client.observe(RunId(runId), prior?.let { EventCursor(it.runId, it.lastSequence) }).collect { update ->
                        val row = dao.turn(turnId) ?: return@collect
                        val current = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                        val snapshot = when (update) {
                            is RuntimeUpdate.Baseline -> update.snapshot.takeIf { it.runId.value == runId && update.cursor == EventCursor(it.runId, it.lastSequence) }
                            is RuntimeUpdate.Event -> current?.let { RunProjection.apply(it, update.envelope) }
                            is RuntimeUpdate.ResyncRequired -> null
                        } ?: (client.snapshot(RunId(runId)) as? SnapshotResult.Found)?.snapshot
                        if (snapshot != null) {
                            saveProjection(turnId, snapshot)
                            if (snapshot.phase.terminal && !RunProjection.occupied(snapshot)) throw CancellationException("Projection reached terminal state")
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    db.withTransaction { dao.turn(turnId)?.let { dao.save(it.copy(error = "同步中断，结果待确认；重新打开应用可恢复观察")) } }
                }
            }
        }
    }
    private suspend fun saveProjection(turnId: String, snapshot: RunSnapshot) {
        val parts = snapshot.outputSegments + snapshot.steps.flatMap { it.output }
        val chunks = mutableListOf<ChunkRow>()
        for (ref in (parts.map { it.ref } + snapshot.artifacts).distinct()) if (dao.chunk(ref.value) == null) {
            val bytes = ByteArrayOutputStream()
            var offset: Long? = 0
            do {
                val read = client.readArtifact(ArtifactReadRequest(ref, offset!!, 65536))
                check(read is ArtifactReadResult.Chunk) { "Output segment unavailable" }
                bytes.write(read.bytes.toByteArray())
                check(bytes.size() <= 512 * 1024) { "Output segment too large" }
                check((read.nextOffset?.let { it > offset!! } ?: true)) { "Non advancing output cursor" }
                offset = read.nextOffset
            } while (offset != null)
            chunks += ChunkRow(ref.value, snapshot.runId.value, bytes.toString("UTF-8"))
        }
        db.withTransaction {
            val row = dao.turn(turnId) ?: return@withTransaction
            val old = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            if (row.runId != snapshot.runId.value || old != null && old.lastSequence > snapshot.lastSequence) return@withTransaction
            dao.chunks(chunks)
            dao.save(row.copy(snapshot = storageJson.encodeToString(snapshot), occupied = RunProjection.occupied(snapshot), error = null))
            val c = dao.conversation(row.conversationId)?.domain() ?: return@withTransaction
            // Older run replays must not replace a newer CLI session.
            if (dao.conversationTurns(c.id.value).lastOrNull { it.runId != null }?.id == row.id && snapshot.sessionRef != null && c.config.agent.name == snapshot.acceptedConfig.agentId.name) {
                dao.save(c.copy(session = snapshot.sessionRef?.value).row())
            }
        }
    }
    private fun TurnRow.domain(content: Map<String, String>): Turn {
        val snapshot = snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
        fun List<OutputSegment>.messages() = sortedBy { it.chunkIndex }.groupBy { it.messageId }.map { (id, parts) -> Message(id, parts.joinToString("") { content[it.ref.value].orEmpty() }) }
        return Turn(TurnId(id), userText, runId?.let(::ExecutionId), snapshot?.let { RunProjection.verifiedPhase(it).domain() },
            snapshot?.outputSegments?.filterNot { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty(),
            snapshot?.steps?.map { Step(it.stepId, it.toolKind, it.summary, it.output.joinToString("\n") { p -> content[p.ref.value].orEmpty() }, it.outcome?.name) }.orEmpty(),
            snapshot?.outputSegments?.filter { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty(),
            error ?: snapshot?.terminalEvidence?.error?.message(), snapshot?.progressSummary, pending, occupied, expanded, storageJson.decodeFromString(expandedSteps),
            snapshot?.artifacts?.mapNotNull { ref -> content[ref.value]?.let { SkillProposal(ref.value, it, DomainAgent.valueOf(snapshot.acceptedConfig.agentId.name)) } }.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).creator != null, snapshot?.artifacts?.any { it.value !in content } == true)
    }
}
