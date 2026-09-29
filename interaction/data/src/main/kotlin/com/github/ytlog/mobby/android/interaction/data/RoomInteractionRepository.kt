package com.github.ytlog.mobby.android.interaction.data

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.data.management.ProjectManager
import com.github.ytlog.mobby.android.interaction.data.management.ConversationManager
import com.github.ytlog.mobby.android.interaction.data.management.TurnManager
import com.github.ytlog.mobby.android.interaction.data.management.DraftManager
import com.github.ytlog.mobby.android.interaction.data.management.MessageManager
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.interaction.domain.AgentId as DomainAgent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
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
    private val projects = ProjectManager(db, now, id)
    private val conversations = ConversationManager(db, now, id)
    private val turns = TurnManager(db, now, system, client, scope, outputCache, execution)
    private val drafts = DraftManager(db)
    private val messages = MessageManager()
    private val importRecovery = CompletableDeferred<Unit>()
    private val startupError = MutableStateFlow<String?>(null)
    private val summaries = combine(dao.conversations().distinctUntilChanged(), dao.conversationActivities().distinctUntilChanged()) { rows, activities ->
        val byConversation = activities.associateBy { it.conversationId }
        rows.map { row ->
            val activity = byConversation[row.id]
            val snapshot = activity?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            ConversationSummary(row.domain(), snapshot?.let { RunStateRules.displayedPhase(it).domain() }, activity?.occupied == true, activity?.executionId?.let(::ExecutionId), snapshot?.deviceOperations?.maxByOrNull { it.order }?.operation)
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
                combine(summaries, dao.observeConversation(selected).distinctUntilChanged(), dao.timeline(selected, limit).distinctUntilChanged()) { conversations, selectedRow, entries ->
                    val c = selectedRow?.domain()
                    val turns = entries.map { entry -> messages.render(entry.turn, entry.chunks.associateBy { it.ref }) }
                    InteractionState(conversations, c?.let { ConversationDetail(it, turns, count > turns.size) }, loading = false)
                }
            })
        }.flowOn(Dispatchers.Default)
    }.combine(projects.observe()) { state, projectList -> state.copy(projects = projectList) }.combine(startupError) { state, error -> state.copy(error = error ?: state.error) }.catch { emit(InteractionState(loading = false, error = AppStrings.cannotReadTheConversationDatabaseDataPreservedRestartThe)) }
        .stateIn(scope, SharingStarted.Eagerly, InteractionState())

    override suspend fun saveSkillProposal(proposal: SkillProposal, markdown: String): DataResult<Skill> {
        suspend fun sourceAvailable() = db.withTransaction {
            val chunk = dao.chunk(proposal.ref) ?: return@withTransaction false
            if (chunk.expired || dao.outputCacheExpired(chunk.runId)) return@withTransaction false
            val snapshot = dao.turnByRun(chunk.runId)?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            snapshot?.artifacts?.any { it.value == proposal.ref } == true && snapshot.acceptedConfig.agentId.name == proposal.agent.name
        }
        if (!sourceAvailable()) return DataResult.Failed(AppStrings.generatedDraftWasCleanedUpOrItsSourceIs)
        when (client.readArtifact(ArtifactReadRequest(ResourceRef(proposal.ref), 0, 1))) {
            ArtifactReadResult.Expired -> {
                outputCache.expire(proposal.ref)
                return DataResult.Failed(AppStrings.generatedDraftWasCleanedUpByTheRetentionPolicy)
            }
            is ArtifactReadResult.Unavailable -> return DataResult.Failed(AppStrings.cannotVerifyTheDraftSourceYetYourEditsAre)
            is ArtifactReadResult.Chunk -> Unit
        }
        // This final transaction is the acceptance point for the user's edited copy.
        // Cleanup after acceptance cannot revoke a save already requested by the user.
        if (!sourceAvailable()) return DataResult.Failed(AppStrings.generatedDraftWasCleanedUpOrItsSourceIs)
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
            ConversationDetail(c, dao.conversationTurns(id.value).map { messages.render(it, content) })
        }
    }
    companion object { const val PAGE_SIZE = 40 }

    init {
        scope.launch { system.status.filter { it.ready && it.connected }.collect { turns.dispatchQueued() } }
        scope.launch {
            try {
                db.withTransaction {
                    dao.allConversations().forEach { row ->
                        val c = row.domain()
                        val pending = c.draft.pendingAttachment
                        if (pending != null && pending.error == null) dao.save(c.copy(draft = c.draft.copy(
                            pendingAttachment = pending.copy(error = AppStrings.theLastImportWasInterruptedRetryOrRemoveIt)
                        )).row())
                    }
                }
                outputCache.compact()
                system.retainAttachmentGrants(dao.allConversations().mapNotNull { it.domain().draft.pendingAttachment?.location }.toSet())
                importRecovery.complete(Unit)
                if (dao.allConversations().isEmpty()) {
                    val profiles = runCatching { system.gateways() }.getOrDefault(emptyList())
                    val selected = runCatching { system.defaultGateway() }.getOrNull()
                    val profile = ConversationGatewayResolver.preferred(profiles, selected)
                    create(NextTurnConfig(profile?.agent ?: DomainAgent.CODEX, profile?.model.orEmpty(), null, "default",
                        profile?.id ?: "CODEX", profile?.version ?: 0))
                } else if (dao.selection().first() == null) {
                    dao.allConversations().firstOrNull { !it.domain().deleted && !it.domain().archived }?.let { dao.select(SelectionRow(conversationId = it.id)) }
                }
                client.connection.filter { it == ConnectionState.CONNECTED }.collect {
                    for (turn in dao.unfinished()) {
                        if (turn.pending && !turns.isInFlight(turn.id)) {
                            if (turn.insertionRunId != null) {
                                val insertion = PreparedInsertion(turn.execution(), ExecutionId(turn.insertionRunId))
                                turns.recordInsertion(insertion, execution.insert(insertion))
                            } else {
                                val found = execution.lookup(TurnId(turn.id))
                                if (turn.queued && found is Submission.Rejected && found.reason == Failure.UNAVAILABLE)
                                    turns.requeueUnsent(TurnId(turn.id))
                                else recordSubmission(turn.execution(), found)
                            }
                        }
                        else if (turn.runId != null) turns.observe(turn.id, turn.runId)
                    }
                    turns.resumeOutput()
                    outputCache.reconcile()
                    turns.dispatchQueued()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { importRecovery.completeExceptionally(e); startupError.value = AppStrings.conversationRecoveryIsIncompleteDataPreservedRestartTheApp }
        }
    }
    override suspend fun conversation(id: ConversationId): Conversation = withContext(Dispatchers.IO) { requireNotNull(dao.conversation(id.value)).domain() }
    override suspend fun awaitAttachmentRecovery() { importRecovery.await() }
    override suspend fun beginAttachment(id: ConversationId, pending: PendingAttachment) {
        importRecovery.await()
        drafts.beginAttachment(id, pending)
    }
    override suspend fun finishAttachment(id: ConversationId, pendingId: String, result: DataResult<Attachment>) {
        drafts.finishAttachment(id, pendingId, result)
        releaseAttachmentSources()
    }
    override suspend fun discardAttachment(id: ConversationId, pendingId: String) {
        drafts.discardAttachment(id, pendingId)
        releaseAttachmentSources()
    }
    private suspend fun releaseAttachmentSources() = withContext(Dispatchers.IO) {
        try { system.retainAttachmentGrants(dao.allConversations().mapNotNull { it.domain().draft.pendingAttachment?.location }.toSet()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { startupError.value = AppStrings.attachmentUpdatedButTemporaryFileCleanupFailedRestartAnd }
    }
    override suspend fun restoreDraft(id: ConversationId, text: String, attachments: List<String>) = drafts.restoreDraft(id, text, attachments)
    override suspend fun setAttachment(id: ConversationId, ref: String, enabled: Boolean) = drafts.setAttachment(id, ref, enabled)
    override suspend fun setSkill(id: ConversationId, ref: String, enabled: Boolean) = drafts.setSkill(id, ref, enabled)
    override suspend fun createSkillConversation(id: ConversationId, creator: String) = conversations.createSkill(id, creator)
    override suspend fun select(id: ConversationId) {
        if (conversations.select(id)) scope.launch { try { outputCache.reconcile(id.value) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { startupError.value = AppStrings.historicalOutputVerificationIsIncompleteCachePreservedPleaseRetry } }
    }
    override suspend fun saveProject(project: Project, createOnly: Boolean) = projects.save(project, createOnly)
    override suspend fun createInProject(config: NextTurnConfig, project: String) = projects.create(config, project)
    override suspend fun create(config: NextTurnConfig) = conversations.create(config)
    override suspend fun editDraft(id: ConversationId, text: String, selectionStart: Int, selectionEnd: Int) = drafts.editDraft(id, text, selectionStart, selectionEnd)
    override suspend fun configure(id: ConversationId, config: NextTurnConfig) = conversations.configure(id, config)
    override suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId) = turns.prepareTurn(conversationId, turnId)
    override suspend fun prepareInsertion(conversationId: ConversationId, turnId: TurnId) = turns.prepareInsertion(conversationId, turnId)
    override suspend fun recordInsertion(insertion: PreparedInsertion, result: Submission) = turns.recordInsertion(insertion, result)
    override suspend fun cancelQueued(turnId: TurnId) = turns.cancelQueued(turnId)
    override suspend fun recordSubmission(turn: TurnExecution, result: Submission) = turns.recordSubmission(turn, result)
    override suspend fun refreshExecution(id: ExecutionId) = turns.refreshExecution(id)
    override suspend fun pendingTurn(conversationId: ConversationId) = turns.pendingTurn(conversationId)
    override suspend fun updateGateway(profile: GatewayProfile) = conversations.updateGateway(profile)
    override suspend fun rename(id: ConversationId, title: String) = conversations.rename(id, title)
    override suspend fun pin(id: ConversationId) = conversations.pin(id)
    override suspend fun setProject(id: ConversationId, project: String?) = projects.move(id, project)
    override suspend fun archive(id: ConversationId, archived: Boolean) = conversations.archive(id, archived)
    override suspend fun delete(id: ConversationId, deleted: Boolean) = conversations.delete(id, deleted)
    override suspend fun expansion(turnId: TurnId, expanded: Boolean) = turns.expansion(turnId, expanded)
    override suspend fun stepExpansion(turnId: TurnId, stepId: String, expanded: Boolean) = turns.stepExpansion(turnId, stepId, expanded)
    override suspend fun anchor(id: ConversationId, messageId: String?, offset: Int) = conversations.anchor(id, messageId, offset)
}
