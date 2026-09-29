package com.github.ytlog.mobby.android.conversation.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.conversation.data.*
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.github.ytlog.mobby.android.runtime.api.*

/** Owns turn submission records and expansion state; a frozen turn keeps its original project and directory. */
internal class TurnManager(private val db: ConversationDatabase, private val now: () -> Long, private val system: SystemPort,
    private val client: RuntimeClient, private val scope: CoroutineScope, private val outputCache: OutputCache,
    private val execution: ExecutionPort) {
    private val dao = db.dao()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val runs = RunSynchronizer(db, client, scope, outputCache, ::dispatchQueued)
    private val dispatchLock = Mutex()
    fun isInFlight(id: String) = id in inFlight
    suspend fun prepareInsertion(conversationId: ConversationId, turnId: TurnId): PrepareInsertionResult = db.withTransaction {
        val c = dao.conversation(conversationId.value)?.domain() ?: return@withTransaction PrepareInsertionResult.Rejected(Failure.UNAVAILABLE)
        if (c.archived || c.deleted) return@withTransaction PrepareInsertionResult.Rejected(Failure.UNAVAILABLE)
        if (dao.conversationTurns(c.id.value).any { it.pending }) return@withTransaction PrepareInsertionResult.Rejected(Failure.PENDING_SUBMISSION)
        if (c.draft.pendingAttachment != null) return@withTransaction PrepareInsertionResult.Rejected(Failure.PENDING_ATTACHMENT)
        if (c.draft.text.isBlank()) return@withTransaction PrepareInsertionResult.Rejected(Failure.EMPTY_DRAFT)
        if (c.draft.attachments.isNotEmpty()) return@withTransaction PrepareInsertionResult.Rejected(Failure.UNSUPPORTED_CAPABILITY)
        val active = dao.earliestOccupied(c.id.value)?.takeIf { it.runId != null && !it.pending && !it.queued }
            ?: return@withTransaction PrepareInsertionResult.Rejected(Failure.UNAVAILABLE)
        if (dao.turn(turnId.value) != null) return@withTransaction PrepareInsertionResult.Rejected(Failure.PENDING_SUBMISSION)
        val target = ExecutionId(active.runId!!)
        val turn = TurnExecution(turnId, c.id, c.draft, c.config, c.session)
        dao.save(TurnRow(turnId.value, c.id.value, c.draft.text, storageJson.encodeToString(StoredConversation.from(c)), now(),
            pending = true, occupied = false, insertionRunId = target.value))
        PrepareInsertionResult.Prepared(PreparedInsertion(turn, target))
    }
    suspend fun recordInsertion(insertion: PreparedInsertion, result: Submission) {
        db.withTransaction {
            val row = dao.turn(insertion.turn.turnId.value)?.takeIf { it.pending && it.insertionRunId == insertion.target.value }
                ?: return@withTransaction
            when (result) {
                is Submission.Accepted -> {
                    dao.save(row.copy(pending = false, error = null))
                    val c = requireNotNull(dao.conversation(row.conversationId)).domain()
                    dao.save(c.copy(draft = ConversationRules.afterSubmission(c.draft, insertion.turn.draft.revision, result), updatedAt = now()).row())
                }
                is Submission.Rejected -> dao.save(row.copy(pending = false, error = when (result.reason) {
                    Failure.UNSUPPORTED_CAPABILITY -> AppStrings.thisAgentDoesNotSupportThisCapabilityYet
                    Failure.INPUT_TOO_LARGE -> AppStrings.requestRejectedTextAndAttachmentsExceedTheInputLimit
                    else -> AppStrings.requestRejectedDraftPreservedCheckTheConnectionAndRetry
                }))
                Submission.Unconfirmed -> dao.save(row.copy(error = AppStrings.requestResultIsUnconfirmedCheckTheOriginalRequestDo))
                is Submission.Queued -> error("A direct insertion cannot be queued")
            }
        }
    }
    suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult {
        val before = dao.conversation(conversationId.value)?.domain() ?: return PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        val selected = before.project?.let { dao.project(it) }?.let { storageJson.decodeFromString<List<String>>(it.skills).toSet() }.orEmpty()
        val skills = if (selected.isEmpty()) emptyList() else (system.skills(before.config.agent) as? DataResult.Loaded)?.value
            ?: return PrepareTurnResult.Rejected(Failure.UNSUPPORTED_CAPABILITY)
        val prepared = db.withTransaction {
        val c = dao.conversation(conversationId.value)?.domain() ?: return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (c.archived || c.deleted) return@withTransaction PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        if (dao.conversationTurns(c.id.value).any { it.pending }) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
        if (c.draft.pendingAttachment != null) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_ATTACHMENT)
        if (c.draft.text.isBlank() && c.draft.attachments.isEmpty()) return@withTransaction PrepareTurnResult.Rejected(Failure.EMPTY_DRAFT)
        if (dao.turn(turnId.value) != null) return@withTransaction PrepareTurnResult.Rejected(Failure.PENDING_SUBMISSION)
        val project = c.project?.let { dao.project(it) }
        if (c.project != null && (project == null || project.workspace != c.config.workspace))
            return@withTransaction PrepareTurnResult.Rejected(Failure.INVALID_CONFIG)
        val names = project?.let { storageJson.decodeFromString<List<String>>(it.skills).toSet() }.orEmpty()
        if (names.isNotEmpty() && c.config.agent != before.config.agent) return@withTransaction PrepareTurnResult.Rejected(Failure.INVALID_CONFIG)
        val bound = skills.filter { it.available && it.name in names }
        if (bound.map { it.name }.toSet() != names) return@withTransaction PrepareTurnResult.Rejected(Failure.UNSUPPORTED_CAPABILITY)
        val capabilities = c.draft.capabilities + listOfNotNull(c.creator) + bound.map { it.ref }
        if (capabilities.size > 24) return@withTransaction PrepareTurnResult.Rejected(Failure.UNSUPPORTED_CAPABILITY)
        val frozen = c.copy(draft = c.draft.copy(capabilities = capabilities))
        val rules = project?.rules.orEmpty()
        val queued = dao.unfinished().isNotEmpty() || dao.earliestQueued() != null
        dao.save(TurnRow(turnId.value, c.id.value, c.draft.text, storageJson.encodeToString(StoredConversation.from(frozen, rules)), now(),
            pending = !queued, occupied = !queued, queued = queued))
        dao.save(c.copy(hasTurns = true, updatedAt = now(), title = if (!c.hasTurns && AppStrings.isDefaultConversationTitle(c.title)) c.draft.text.lineSequence().first().take(40).ifBlank { AppStrings.newConversation } else c.title,
            draft = if (queued) ConversationRules.afterQueue(c.draft, c.draft.revision) else c.draft).row())
        if (queued) PrepareTurnResult.Queued(turnId)
        else {
            inFlight.add(turnId.value)
            PrepareTurnResult.Prepared(TurnExecution(turnId, c.id, frozen.draft, c.config, c.session, c.creator != null, rules))
        }
        }
        if (prepared is PrepareTurnResult.Queued) dispatchQueued()
        return prepared
    }
    suspend fun recordSubmission(turn: TurnExecution, result: Submission) {
        db.withTransaction {
            val row = dao.turn(turn.turnId.value) ?: return@withTransaction
            if (!row.pending) return@withTransaction // Replayed acknowledgements must not clear a newer draft.
            when (result) {
                is Submission.Accepted -> {
                    dao.save(row.copy(runId = result.executionId.value, pending = false, occupied = true, queued = false, error = null))
                    val c = requireNotNull(dao.conversation(turn.conversationId.value)).domain()
                    dao.save(c.copy(draft = ConversationRules.afterSubmission(c.draft, turn.draft.revision, result)).row())
                }
                is Submission.Queued -> error("A queued turn has not been submitted")
                is Submission.Rejected -> dao.save(row.copy(pending = false, occupied = false, queued = false, error = when (result.reason) {
                    Failure.PENDING_ATTACHMENT -> AppStrings.requestRejectedFinishOrRemovePendingAttachments
                    Failure.INPUT_TOO_LARGE -> AppStrings.requestRejectedTextAndAttachmentsExceedTheInputLimit
                    Failure.BUSY -> AppStrings.requestRejectedAnotherTaskIsUsingTheRuntimeDraft
                    Failure.INVALID_CONFIG -> AppStrings.requestRejectedCheckGatewayModelPermissionsOrAnExisting
                    Failure.UNSUPPORTED_CAPABILITY -> AppStrings.requestRejectedThisCapabilityIsUnavailableDraftPreserved
                    else -> AppStrings.requestRejectedDraftPreservedCheckTheConnectionAndRetry
                }))
                Submission.Unconfirmed -> dao.save(row.copy(error = AppStrings.requestResultIsUnconfirmedCheckTheOriginalRequestDo))
            }
        }
        inFlight.remove(turn.turnId.value)
        if (result is Submission.Accepted) observe(turn.turnId.value, result.executionId.value)
        else if (result is Submission.Rejected) dispatchQueued()
    }
    fun dispatchQueued() {
        scope.launch {
            dispatchLock.withLock {
                while (client.connection.value == ConnectionState.CONNECTED && system.status.first().ready) {
                    val next = db.withTransaction {
                        if (dao.unfinished().isNotEmpty()) return@withTransaction null
                        val row = dao.earliestQueued() ?: return@withTransaction null
                        val frozen = row.execution()
                        val current = dao.conversation(row.conversationId)?.domain()
                        val session = current?.sessions?.get(frozen.config.agent)
                            ?: current?.session?.takeIf { current.config.agent == frozen.config.agent }
                            ?: frozen.session
                        dao.save(row.copy(pending = true, occupied = true))
                        frozen.copy(session = session)
                    } ?: break
                    inFlight.add(next.turnId.value)
                    val result = try { execution.submit(next) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { Submission.Unconfirmed }
                    if (result is Submission.Rejected && result.reason == Failure.BUSY) {
                        db.withTransaction { dao.turn(next.turnId.value)?.takeIf { it.pending }?.let {
                            dao.save(it.copy(queued = true, pending = false, occupied = false))
                        } }
                        inFlight.remove(next.turnId.value)
                        scope.launch { delay(2_000); dispatchQueued() }
                        break
                    }
                    recordSubmission(next, result)
                    if (result is Submission.Accepted || result == Submission.Unconfirmed) break
                }
            }
        }
    }
    suspend fun cancelQueued(turnId: TurnId): OperationResult = db.withTransaction {
        val row = dao.turn(turnId.value)?.takeIf { it.queued && !it.pending } ?: return@withTransaction OperationResult.Failed(AppStrings.queuedMessageIsAlreadyRunning)
        dao.save(row.copy(queued = false, error = AppStrings.queuedMessageCancelledRestoreToInput))
        OperationResult.Done
    }
    suspend fun requeueUnsent(turnId: TurnId) = db.withTransaction {
        dao.turn(turnId.value)?.takeIf { it.queued && it.pending && it.runId == null }?.let {
            dao.save(it.copy(pending = false, occupied = false, error = null))
        }
    }
    suspend fun pendingTurn(conversationId: ConversationId): TurnExecution? = dao.conversationTurns(conversationId.value).firstOrNull { it.pending && it.insertionRunId == null }?.execution()
    suspend fun refreshExecution(id: ExecutionId) = runs.refresh(id)
    suspend fun expansion(turnId: TurnId, expanded: Boolean) = db.withTransaction {
        dao.turn(turnId.value)?.let { dao.save(it.copy(expanded = expanded)) }; Unit
    }
    suspend fun stepExpansion(turnId: TurnId, stepId: String, expanded: Boolean) = db.withTransaction {
        dao.turn(turnId.value)?.let {
            val old = storageJson.decodeFromString<Set<String>>(it.expandedSteps)
            dao.save(it.copy(expandedSteps = storageJson.encodeToString(if (expanded) old + stepId else old - stepId)))
        }; Unit
    }
    suspend fun resumeOutput() = runs.resumeOutput()
    fun observe(turnId: String, runId: String) = runs.observe(turnId, runId)
}
