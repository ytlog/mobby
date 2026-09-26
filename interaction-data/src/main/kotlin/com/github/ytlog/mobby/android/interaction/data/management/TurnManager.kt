package com.github.ytlog.mobby.android.interaction.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.data.*
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.interaction.domain.AgentId as DomainAgent

/** Owns turn submission records and expansion state; a frozen turn keeps its original project and directory. */
internal class TurnManager(private val db: InteractionDatabase, private val now: () -> Long, private val system: SystemPort,
    private val client: RuntimeClient, private val scope: CoroutineScope, private val outputCache: OutputCache) {
    private val dao = db.dao()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val observers = mutableMapOf<String, Job>()
    fun isInFlight(id: String) = id in inFlight
    suspend fun prepareTurn(conversationId: ConversationId, turnId: TurnId): PrepareTurnResult {
        val before = dao.conversation(conversationId.value)?.domain() ?: return PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
        val selected = before.project?.let { dao.project(it) }?.let { storageJson.decodeFromString<List<String>>(it.skills).toSet() }.orEmpty()
        val skills = if (selected.isEmpty()) emptyList() else (system.skills(before.config.agent) as? DataResult.Loaded)?.value
            ?: return PrepareTurnResult.Rejected(Failure.UNSUPPORTED_CAPABILITY)
        return db.withTransaction {
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
        dao.save(TurnRow(turnId.value, c.id.value, c.draft.text, storageJson.encodeToString(StoredConversation.from(frozen, rules)), now()))
        dao.save(c.copy(hasTurns = true, updatedAt = now(), title = if (!c.hasTurns && AppStrings.isDefaultConversationTitle(c.title)) c.draft.text.lineSequence().first().take(40).ifBlank { AppStrings.newConversation } else c.title).row())
        inFlight.add(turnId.value)
        PrepareTurnResult.Prepared(TurnExecution(turnId, c.id, frozen.draft, c.config, c.session, c.creator != null, rules))
        }
    }
    suspend fun recordSubmission(turn: TurnExecution, result: Submission) {
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
    }
    suspend fun pendingTurn(conversationId: ConversationId): TurnExecution? = dao.conversationTurns(conversationId.value).firstOrNull { it.pending }?.execution()
    suspend fun refreshExecution(id: ExecutionId) {
        val row = dao.turnByRun(id.value) ?: return
        val deadline = System.nanoTime() + 3_000_000_000L
        do {
            val snapshot = (client.snapshot(RunId(id.value)) as? SnapshotResult.Found)?.snapshot ?: return
            saveProjection(row.id, snapshot)
            if (!RunProjection.occupied(snapshot)) return
            delay(100)
        } while (System.nanoTime() < deadline)
    }
    suspend fun expansion(turnId: TurnId, expanded: Boolean) = db.withTransaction {
        dao.turn(turnId.value)?.let { dao.save(it.copy(expanded = expanded)) }; Unit
    }
    suspend fun stepExpansion(turnId: TurnId, stepId: String, expanded: Boolean) = db.withTransaction {
        dao.turn(turnId.value)?.let {
            val old = storageJson.decodeFromString<Set<String>>(it.expandedSteps)
            dao.save(it.copy(expandedSteps = storageJson.encodeToString(if (expanded) old + stepId else old - stepId)))
        }; Unit
    }
    fun observe(turnId: String, runId: String) {
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
                    db.withTransaction { dao.turn(turnId)?.let { dao.save(it.copy(error = AppStrings.syncInterruptedResultUnconfirmedReopenTheAppToResume)) } }
                }
            }
        }
    }
    private suspend fun saveProjection(turnId: String, snapshot: RunSnapshot) {
        val parts = snapshot.outputSegments + snapshot.steps.flatMap { it.output }
        val chunks = mutableListOf<ChunkRow>()
        val cached = dao.chunkRefs(snapshot.runId.value).toHashSet()
        for (ref in (parts.map { it.ref } + snapshot.artifacts).distinct()) if (ref.value !in cached) {
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
            val c = dao.conversation(row.conversationId)?.domain() ?: return@withTransaction
            val sessionId = snapshot.sessionRef?.value ?: return@withTransaction
            val agent = snapshot.acceptedConfig.agentId.name
            // A delayed replay may update only that engine's session, and only if it is still the latest run for that engine.
            val latest = dao.conversationTurns(c.id.value).lastOrNull { turn ->
                turn.runId != null && runCatching { storageJson.decodeFromString<StoredConversation>(turn.frozen).agent }.getOrNull() == agent
            }
            val frozen = storageJson.decodeFromString<StoredConversation>(row.frozen)
            if (latest?.id == row.id && frozen.workspace == c.config.workspace && frozen.project == c.project)
                dao.save(ConversationRules.rememberSession(c, DomainAgent.valueOf(agent), sessionId).row())
        }
        if (snapshot.phase.terminal && !RunProjection.occupied(snapshot)) outputCache.compact()
    }
}
