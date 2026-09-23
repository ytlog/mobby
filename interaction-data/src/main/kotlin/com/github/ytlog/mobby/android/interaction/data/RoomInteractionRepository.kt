package com.github.ytlog.mobby.android.interaction.data

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.interaction.domain.AgentId as DomainAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomInteractionRepository(
    private val db: InteractionDatabase, private val client: RuntimeClient, private val system: SystemPort,
    private val scope: CoroutineScope, private val execution: ExecutionPort,
    private val now: () -> Long = System::currentTimeMillis, private val id: () -> String = { UUID.randomUUID().toString() },
    cacheBudgetBytes: Long = 256L * 1024 * 1024
) : InteractionRepository {
    private val dao = db.dao()
    private val outputCache = OutputCache(db, client, cacheBudgetBytes)
    private val importRecovery = CompletableDeferred<Unit>()
    private val startupError = MutableStateFlow<String?>(null)
    private val observers = mutableMapOf<String, Job>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val summaries = combine(dao.conversations().distinctUntilChanged(), dao.conversationActivities().distinctUntilChanged()) { rows, activities ->
        val byConversation = activities.associateBy { it.conversationId }
        rows.map { row ->
            val activity = byConversation[row.id]
            val snapshot = activity?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            ConversationSummary(row.domain(), snapshot?.let { RunProjection.verifiedPhase(it).domain() }, activity?.occupied == true)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)
    private data class Window(val id: String, val count: Int, val baseline: Int)
    private val window = MutableStateFlow<Window?>(null)
    override val state: StateFlow<InteractionState> = dao.selection().distinctUntilChanged().flatMapLatest { selected ->
        if (selected == null) summaries.map { InteractionState(it, loading = false) }
        else flow {
            val total = dao.turnCount(selected).first()
            val anchor = dao.conversation(selected)?.domain()?.anchor
            val anchorTurn = anchor?.substringAfter(':', "")?.substringBefore(':')?.let { dao.turn(it) }?.takeIf { it.conversationId == selected }
            val needed = listOfNotNull(anchorTurn, dao.earliestOccupied(selected)).map { dao.countThrough(selected, it.createdAt, it.id) }.maxOrNull() ?: 0
            window.value = Window(selected, maxOf(PAGE_SIZE, needed), total)
            emitAll(combine(window.filterNotNull().filter { it.id == selected }, dao.turnCount(selected).distinctUntilChanged()) { page, count ->
                page.count + (count - page.baseline).coerceAtLeast(0) to count
            }.distinctUntilChanged().flatMapLatest { (limit, count) ->
                combine(summaries, dao.timeline(selected, limit).distinctUntilChanged()) { conversations, entries ->
                    val c = conversations.firstOrNull { it.conversation.id.value == selected }?.conversation
                    val turns = entries.map { entry -> entry.turn.domain(entry.chunks.associateBy { it.ref }) }
                    InteractionState(conversations, c?.let { ConversationDetail(it, turns, count > turns.size) }, loading = false)
                }
            })
        }.flowOn(Dispatchers.Default)
    }.combine(dao.projects()) { state, projects -> state.copy(projects = projects.map { Project(it.name, it.defaultWorkspace) }) }.combine(startupError) { state, error -> state.copy(error = error ?: state.error) }.catch { emit(InteractionState(loading = false, error = "无法读取会话数据库；原数据已保留，请重启应用后重试")) }
        .stateIn(scope, SharingStarted.Eagerly, InteractionState())

    override suspend fun saveSkillProposal(proposal: SkillProposal, markdown: String): DataResult<Skill> {
        suspend fun sourceAvailable() = db.withTransaction {
            val chunk = dao.chunk(proposal.ref) ?: return@withTransaction false
            if (chunk.expired || dao.outputCacheExpired(chunk.runId)) return@withTransaction false
            val snapshot = dao.turnByRun(chunk.runId)?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            snapshot?.artifacts?.any { it.value == proposal.ref } == true && snapshot.acceptedConfig.agentId.name == proposal.agent.name
        }
        if (!sourceAvailable()) return DataResult.Failed("生成草稿已清理或来源失效，编辑内容仍保留")
        when (client.readArtifact(ArtifactReadRequest(ResourceRef(proposal.ref), 0, 1))) {
            ArtifactReadResult.Expired -> {
                outputCache.expire(proposal.ref)
                return DataResult.Failed("生成草稿已按保留策略清理，编辑内容仍保留")
            }
            is ArtifactReadResult.Unavailable -> return DataResult.Failed("暂时无法核对草稿来源，编辑内容仍保留，请重试")
            is ArtifactReadResult.Chunk -> Unit
        }
        // This final transaction is the acceptance point for the user's edited copy.
        // Cleanup after acceptance cannot revoke a save already requested by the user.
        if (!sourceAvailable()) return DataResult.Failed("生成草稿已清理或来源失效，编辑内容仍保留")
        return system.importSkill(proposal.agent, markdown)
    }

    override suspend fun loadEarlier(id: ConversationId) {
        val current = state.value.selected?.takeIf { it.conversation.id == id } ?: return
        val total = dao.turnCount(id.value).first()
        window.update { page -> if (page?.id == id.value) Window(id.value, current.turns.size + PAGE_SIZE, total) else page }
    }
    override suspend fun revealTurn(id: ConversationId, turn: TurnId) {
        val row = dao.turn(turn.value)?.takeIf { it.conversationId == id.value } ?: return
        val total = dao.turnCount(id.value).first()
        val needed = dao.countThrough(id.value, row.createdAt, row.id)
        val loaded = state.value.selected?.takeIf { it.conversation.id == id }?.turns?.size ?: PAGE_SIZE
        window.update { page -> if (page?.id == id.value) Window(id.value, maxOf(loaded, needed), total) else page }
    }
    override suspend fun history(id: ConversationId): ConversationDetail = withContext(Dispatchers.Default) {
        outputCache.reconcile(id.value)
        db.withTransaction {
            val c = requireNotNull(dao.conversation(id.value)).domain()
            val content = dao.historyChunks(id.value).associateBy { it.ref }
            ConversationDetail(c, dao.conversationTurns(id.value).map { it.domain(content) })
        }
    }
    companion object { const val PAGE_SIZE = 40 }

    init {
        scope.launch {
            try {
                db.withTransaction {
                    dao.allConversations().forEach { row ->
                        val c = row.domain()
                        val pending = c.draft.pendingAttachment
                        if (pending != null && pending.error == null) dao.save(c.copy(draft = c.draft.copy(
                            pendingAttachment = pending.copy(error = "上次导入已中断，请重试或移除；原草稿已保留")
                        )).row())
                    }
                }
                outputCache.compact()
                system.retainAttachmentGrants(dao.allConversations().mapNotNull { it.domain().draft.pendingAttachment?.location }.toSet())
                importRecovery.complete(Unit)
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
                    outputCache.reconcile()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { importRecovery.completeExceptionally(e); startupError.value = "会话恢复未完成，原数据已保留；请重启应用后重试" }
        }
    }
    override suspend fun conversation(id: ConversationId): Conversation = withContext(Dispatchers.IO) { requireNotNull(dao.conversation(id.value)).domain() }
    override suspend fun awaitAttachmentRecovery() { importRecovery.await() }
    override suspend fun beginAttachment(id: ConversationId, pending: PendingAttachment) {
        importRecovery.await()
        mutate(id) { c ->
            require(!c.archived && !c.deleted && c.config.workspace == pending.workspace && c.draft.attachments.size < 4)
            require(c.draft.pendingAttachment?.let { it.error != null } != false)
            c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, pendingAttachment = pending))
        }
    }
    override suspend fun finishAttachment(id: ConversationId, pendingId: String, result: DataResult<Attachment>) {
        mutate(id) { c ->
            val pending = c.draft.pendingAttachment
            if (pending?.id != pendingId) c else when (result) {
                is DataResult.Failed -> c.copy(draft = c.draft.copy(pendingAttachment = pending.copy(error = result.message)))
                is DataResult.Loaded -> {
                    if (c.archived || c.deleted || c.config.workspace != pending.workspace || (c.draft.attachments + result.value.ref).distinct().size > 4)
                        c.copy(draft = c.draft.copy(pendingAttachment = pending.copy(error = "会话或附件状态已变化，请恢复会话后重试或移除")))
                    else c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, attachments = (c.draft.attachments + result.value.ref).distinct(), pendingAttachment = null))
                }
            }
        }
        releaseAttachmentSources()
    }
    override suspend fun discardAttachment(id: ConversationId, pendingId: String) {
        mutate(id) { c ->
            if (c.draft.pendingAttachment?.id == pendingId) c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, pendingAttachment = null)) else c
        }
        releaseAttachmentSources()
    }
    private suspend fun releaseAttachmentSources() = withContext(Dispatchers.IO) {
        try { system.retainAttachmentGrants(dao.allConversations().mapNotNull { it.domain().draft.pendingAttachment?.location }.toSet()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { startupError.value = "附件已更新，但临时文件清理失败；请重启后重试" }
    }
    override suspend fun restoreDraft(id: ConversationId, text: String, attachments: List<String>) = mutate(id) { c ->
        require(!c.archived && !c.deleted && attachments.size <= 4)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, text = text, selectionStart = text.length, selectionEnd = text.length, attachments = attachments))
    }
    override suspend fun setAttachment(id: ConversationId, ref: String, enabled: Boolean) = mutate(id) { c ->
        require(!c.archived && !c.deleted)
        val refs = if (enabled) (c.draft.attachments + ref).distinct() else c.draft.attachments - ref
        require(refs.size <= 4)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, attachments = refs))
    }
    override suspend fun setSkill(id: ConversationId, ref: String, enabled: Boolean) = mutate(id) { c ->
        require(!enabled || ref.startsWith("skill:${c.config.agent.name}:") || ref.startsWith("plugin:device:"))
        val refs = if (enabled) c.draft.capabilities + ref else c.draft.capabilities - ref
        require((refs + listOfNotNull(c.creator)).size <= 24)
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
        if (!c.deleted) {
            dao.select(SelectionRow(conversationId = id.value))
            scope.launch { try { outputCache.reconcile(id.value) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { startupError.value = "历史输出核对未完成，已保留缓存；请重试" } }
        }
    }
    override suspend fun saveProject(project: Project, createOnly: Boolean): OperationResult = db.withTransaction {
        val existing = dao.project(project.name)
        if (project.name.isBlank() || project.name != project.name.trim() || project.name.any { it.isISOControl() } || (existing == null && project.name.length > 80))
            return@withTransaction OperationResult.Failed("项目名称不能为空，最多 80 字")
        if (createOnly && existing != null) return@withTransaction OperationResult.Failed("同名项目已存在，请使用其他名称")
        if (project.defaultWorkspace.isBlank()) return@withTransaction OperationResult.Failed("请选择默认工作区")
        dao.save(ProjectRow(project.name, project.defaultWorkspace))
        OperationResult.Done
    }
    override suspend fun createInProject(config: NextTurnConfig, project: String, workspaceOverride: String?): ConversationId = db.withTransaction {
        val defaults = requireNotNull(dao.project(project)) { "Project no longer exists" }
        val c = Conversation(ConversationId(id()), config.copy(workspace = workspaceOverride ?: defaults.defaultWorkspace), project = defaults.name, updatedAt = now())
        dao.save(c.row()); dao.select(SelectionRow(conversationId = c.id.value)); c.id
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
        val changed = ConversationRules.applyConfig(old, config).copy(updatedAt = now())
        dao.save(changed.row()); dao.select(SelectionRow(conversationId = changed.id.value)); changed.id
    }
    override suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult = db.withTransaction {
        val c = dao.conversation(conversationId.value)?.domain() ?: return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (c.archived || c.deleted) return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (dao.conversationTurns(c.id.value).any { it.pending }) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
        if (c.draft.pendingAttachment != null) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_ATTACHMENT)
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
                    Failure.PENDING_ATTACHMENT -> "请求未接纳：请完成或移除待处理附件"
                    Failure.INPUT_TOO_LARGE -> "请求未接纳：文字与附件合计超出输入上限，请缩短文字或移除附件；草稿已保留"
                    Failure.BUSY -> "请求未接纳：已有任务占用运行环境；草稿已保留"
                    Failure.INVALID_CONFIG -> "请求未接纳：请检查网关、模型、权限，或是否已有同名 mobby- 技能；草稿已保留"
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
    override suspend fun setProject(id: ConversationId, project: String?) = db.withTransaction {
        require(project == null || dao.project(project) != null) { "Project no longer exists" }
        mutate(id) { it.copy(project = project) }
    }
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
            if (dao.outputCacheExpired(snapshot.runId.value)) {
                chunks += ChunkRow(ref.value, snapshot.runId.value, "", true)
                continue
            }
            val bytes = ByteArrayOutputStream()
            var offset: Long? = 0
            var expired = false
            do {
                val read = client.readArtifact(ArtifactReadRequest(ref, offset!!, 65536))
                if (read == ArtifactReadResult.Expired) { bytes.reset(); expired = true; break }
                check(read is ArtifactReadResult.Chunk) { "Output segment unavailable" }
                bytes.write(read.bytes.toByteArray())
                check(bytes.size() <= 512 * 1024) { "Output segment too large" }
                check((read.nextOffset?.let { it > offset!! } ?: true)) { "Non advancing output cursor" }
                offset = read.nextOffset
            } while (offset != null)
            chunks += ChunkRow(ref.value, snapshot.runId.value, bytes.toString("UTF-8"), expired)
        }
        db.withTransaction {
            val row = dao.turn(turnId) ?: return@withTransaction
            val old = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            if (row.runId != snapshot.runId.value || old != null && old.lastSequence > snapshot.lastSequence) return@withTransaction
            outputCache.save(chunks)
            dao.save(row.copy(snapshot = storageJson.encodeToString(snapshot), occupied = RunProjection.occupied(snapshot), error = null))
            if (snapshot.phase.terminal && !RunProjection.occupied(snapshot)) outputCache.compact()
            val c = dao.conversation(row.conversationId)?.domain() ?: return@withTransaction
            val sessionId = snapshot.sessionRef?.value ?: return@withTransaction
            val agent = snapshot.acceptedConfig.agentId.name
            // A delayed replay may update only that engine's session, and only if it is still the latest run for that engine.
            val latest = dao.conversationTurns(c.id.value).lastOrNull { turn ->
                turn.runId != null && runCatching { storageJson.decodeFromString<StoredConversation>(turn.frozen).agent }.getOrNull() == agent
            }
            if (latest?.id == row.id) dao.save(ConversationRules.rememberSession(c, DomainAgent.valueOf(agent), sessionId).row())
        }
    }
    private fun TurnRow.domain(content: Map<String, ChunkRow>): Turn {
        val snapshot = snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
        fun List<OutputSegment>.render(separator: String = ""): String = buildString {
            var previousExpired = false
            for (part in this@render) {
                val row = content[part.ref.value] ?: continue
                if (row.expired && previousExpired) continue
                if (isNotEmpty()) append(if (row.expired || previousExpired) "\n" else separator)
                append(if (row.expired) "输出已按保留策略清理" else row.text)
                previousExpired = row.expired
            }
        }
        fun List<OutputSegment>.messages() = sortedBy { it.chunkIndex }.groupBy { it.messageId }.map { (id, parts) -> Message(id, parts.render(), parts.minOf { it.chunkIndex }) }
        val pendingOrder = ((snapshot?.outputSegments.orEmpty() + snapshot?.steps.orEmpty().flatMap { it.output }).maxOfOrNull { it.chunkIndex } ?: -1L) + 1
        var nextOrder = pendingOrder
        return Turn(TurnId(id), userText, runId?.let(::ExecutionId), snapshot?.let { RunProjection.verifiedPhase(it).domain() },
            snapshot?.outputSegments?.filterNot { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty() +
                if (snapshot?.artifacts?.any { content[it.value]?.expired == true } == true) listOf(Message("retained-artifact-notice", "技能草稿已按保留策略清理")) else emptyList(),
            snapshot?.steps?.map { step ->
                val order = if (step.order >= 0) step.order else step.output.minOfOrNull { it.chunkIndex } ?: nextOrder++
                val text = step.output.sortedBy { it.chunkIndex }.render(if (step.body is StepBody.Thinking) "" else "\n")
                val outcome = step.outcome?.name
                when (val body = step.body) {
                    StepBody.Thinking -> Step.Thinking(step.stepId, text, outcome, order)
                    is StepBody.Command -> Step.Command(step.stepId, body.command, text, outcome, order)
                    is StepBody.FileRead -> Step.FileRead(step.stepId, body.path, text, outcome, order)
                    is StepBody.FileWrite -> Step.FileWrite(step.stepId, body.path, text, outcome, order)
                    is StepBody.FileDiff -> Step.FileDiff(step.stepId, body.paths, text, outcome, order)
                    is StepBody.Action -> Step.Action(step.stepId, body.name, body.detail, text, outcome, order)
                }
            }.orEmpty(),
            snapshot?.outputSegments?.filter { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty(),
            if (error == OutputCache.VERIFICATION_WARNING) listOfNotNull(snapshot?.terminalEvidence?.error?.message(), error).joinToString("\n")
            else error ?: snapshot?.terminalEvidence?.error?.message(), snapshot?.progress?.domain(), pending, occupied, expanded, storageJson.decodeFromString(expandedSteps),
            snapshot?.artifacts?.mapNotNull { ref -> content[ref.value]?.takeUnless { it.expired }?.let { SkillProposal(ref.value, it.text, DomainAgent.valueOf(snapshot.acceptedConfig.agentId.name)) } }.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).creator != null, snapshot?.artifacts?.any { it.value !in content } == true, storageJson.decodeFromString<StoredConversation>(frozen).attachments,
            snapshot?.pendingApprovals?.map { PermissionRequest(it.approvalId, it.revision, it.subject.domain()) }.orEmpty())
    }
}

private fun com.github.ytlog.mobby.android.runtime.api.ProgressNotice.domain() = when (this) {
    com.github.ytlog.mobby.android.runtime.api.ProgressNotice.OUTPUT_TRUNCATED ->
        com.github.ytlog.mobby.android.interaction.domain.ProgressNotice.OUTPUT_TRUNCATED
}

private fun ApprovalSubject.domain(): PermissionSubject = when (this) {
    is ApprovalSubject.Command -> PermissionSubject.Command(command)
    is ApprovalSubject.FileRead -> PermissionSubject.FileRead(path, offset, limit)
    is ApprovalSubject.FileWrite -> PermissionSubject.FileWrite(path, content)
    is ApprovalSubject.FileDiff -> PermissionSubject.FileDiff(paths, diff)
    is ApprovalSubject.Action -> PermissionSubject.Action(name, detail)
}
