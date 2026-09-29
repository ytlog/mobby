package com.github.ytlog.mobby.android.conversation.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.conversation.data.*
import com.github.ytlog.mobby.android.conversation.domain.ConversationRules
import com.github.ytlog.mobby.android.conversation.domain.ExecutionId
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import com.github.ytlog.mobby.android.conversation.domain.AgentId as DomainAgent

/** Sole writer of runtime snapshots and their conversation/session projection. */
internal class RunSynchronizer(private val db: ConversationDatabase, private val client: RuntimeClient,
    private val scope: CoroutineScope, cache: OutputCache, private val onReleased: () -> Unit) {
    private val dao = db.dao()
    private val observers = mutableMapOf<String, Job>()
    private val output = RunOutputSynchronizer(db, client, scope, cache)

    suspend fun resumeOutput() {
        for (candidate in dao.outputSyncCandidates()) {
            val snapshot = storageJson.decodeFromString<RunSnapshot>(candidate.snapshot)
            val cached = dao.chunkRefs(candidate.runId).toHashSet()
            val refs = (snapshot.outputSegments + snapshot.steps.flatMap { it.output }).map { it.ref } + snapshot.artifacts
            if (candidate.error == AppStrings.OUTPUT_SYNC_MARKER || refs.any { it.value !in cached }) output.request(snapshot.runId)
        }
    }

    suspend fun refresh(id: ExecutionId) {
        val row = dao.turnByRun(id.value) ?: return
        withTimeoutOrNull(3_000) {
            do {
                val snapshot = (client.snapshot(RunId(id.value)) as? SnapshotResult.Found)?.snapshot ?: return@withTimeoutOrNull
                save(row.id, snapshot)
                if (!RunStateRules.occupiesExecution(snapshot)) return@withTimeoutOrNull
                delay(100)
            } while (true)
        }
    }

    fun observe(turnId: String, runId: String) {
        synchronized(observers) {
            if (observers[runId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var backoff = 500L
                while (isActive) {
                    try {
                        val prior = dao.turn(turnId)?.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                        client.observe(RunId(runId), prior?.let { EventCursor(it.runId, it.lastSequence) }).collect { update ->
                            val row = dao.turn(turnId) ?: throw CancellationException("Turn removed")
                            val current = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                            val snapshot = when (update) {
                                is RuntimeUpdate.Baseline -> update.snapshot.takeIf { it.runId.value == runId && update.cursor == EventCursor(it.runId, it.lastSequence) }
                                is RuntimeUpdate.Event -> current?.let { RunProjection.apply(it, update.envelope) }
                                is RuntimeUpdate.ResyncRequired -> null
                            } ?: (client.snapshot(RunId(runId)) as? SnapshotResult.Found)?.snapshot
                                ?: error("Run snapshot unavailable")
                            save(turnId, snapshot)
                            backoff = 500L
                            if (!RunStateRules.occupiesExecution(snapshot)) throw CancellationException("Run released")
                        }
                        error("Run observation ended before release")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        db.withTransaction { dao.turn(turnId)?.let { row ->
                            if (row.error == null) dao.save(row.copy(error = AppStrings.STATE_SYNC_MARKER))
                        } }
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(5_000)
                    }
                }
            }
            observers[runId] = job
            job.invokeOnCompletion { synchronized(observers) { if (observers[runId] === job) observers.remove(runId) } }
            job.start()
        }
    }

    private suspend fun save(turnId: String, snapshot: RunSnapshot) {
        val saved = db.withTransaction {
            val row = dao.turn(turnId) ?: return@withTransaction false
            val old = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
            if (row.runId != snapshot.runId.value || old != null && old.lastSequence > snapshot.lastSequence) return@withTransaction false
            dao.save(row.copy(snapshot = storageJson.encodeToString(snapshot), occupied = RunStateRules.occupiesExecution(snapshot),
                error = row.error.takeUnless { it == AppStrings.STATE_SYNC_MARKER }))
            val c = dao.conversation(row.conversationId)?.domain()
            val sessionId = snapshot.sessionRef?.value
            if (c != null && sessionId != null) {
                val agent = snapshot.acceptedConfig.agentId.name
                val latest = dao.conversationTurns(c.id.value).lastOrNull { turn ->
                    turn.runId != null && storageJson.decodeFromString<StoredConversation>(turn.frozen).agent == agent
                }
                val frozen = storageJson.decodeFromString<StoredConversation>(row.frozen)
                if (latest?.id == row.id && frozen.workspace == c.config.workspace && frozen.project == c.project)
                    dao.save(ConversationRules.rememberSession(c, DomainAgent.valueOf(agent), sessionId).row())
            }
            true
        }
        if (saved) {
            output.request(snapshot.runId)
            if (!RunStateRules.occupiesExecution(snapshot)) {
                onReleased()
                synchronized(observers) { observers[snapshot.runId.value]?.cancel() }
            }
        }
    }
}
