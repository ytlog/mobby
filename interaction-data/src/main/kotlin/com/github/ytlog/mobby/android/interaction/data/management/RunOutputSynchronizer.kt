package com.github.ytlog.mobby.android.interaction.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.data.*
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream

/** Independent output workers: slow/missing artifacts never hold up authoritative run state. */
internal class RunOutputSynchronizer(private val db: InteractionDatabase, private val client: RuntimeClient,
    private val scope: CoroutineScope, private val cache: OutputCache) {
    private val dao = db.dao()
    private val workers = mutableMapOf<String, Channel<Unit>>()

    fun request(runId: RunId) {
        val id = runId.value
        synchronized(workers) {
            workers[id]?.let { if (it.trySend(Unit).isSuccess) return }
            val input = Channel<Unit>(Channel.CONFLATED)
            workers[id] = input
            input.trySend(Unit)
            scope.launch {
                try {
                    input.receive()
                    var backoff = 500L
                    while (isActive) {
                        // Requests are only wakeups. Delayed scans cannot replace the persisted checkpoint.
                        val stored = dao.turnByRun(id)?.snapshot ?: return@launch
                        val current = storageJson.decodeFromString<RunSnapshot>(stored)
                        check(current.runId.value == id) { "Run output checkpoint mismatch" }
                        val loaded = try {
                            load(current)
                            if (!RunStateRules.occupiesExecution(current)) cache.compact()
                            backoff = 500L
                            true
                        } catch (e: CancellationException) {
                            if (e !is TimeoutCancellationException) throw e
                            warn(id); false
                        } catch (_: Exception) { warn(id); false }
                        val (next, complete) = synchronized(workers) {
                            val queued = input.tryReceive().isSuccess
                            val complete = !queued && loaded && !RunStateRules.occupiesExecution(current)
                            if (complete) { input.close(); workers.remove(id) }
                            queued to complete
                        }
                        if (next) continue
                        if (complete) return@launch
                        if (loaded) input.receive()
                        else {
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(5_000)
                            input.tryReceive()
                        }
                    }
                } finally {
                    synchronized(workers) { if (workers[id] === input) workers.remove(id) }
                    input.close()
                }
            }
        }
    }

    private suspend fun warn(runId: String) = db.withTransaction {
        dao.turnByRun(runId)?.let { row ->
            if (row.error == null || row.error == OutputCache.VERIFICATION_WARNING)
                dao.save(row.copy(error = AppStrings.OUTPUT_SYNC_MARKER))
        }
    }

    private suspend fun load(snapshot: RunSnapshot) {
        val runId = snapshot.runId.value
        val refs = (snapshot.outputSegments + snapshot.steps.flatMap { it.output }).map { it.ref } + snapshot.artifacts
        val cached = dao.chunkRefs(runId).toHashSet()
        for (ref in refs.distinct()) if (ref.value !in cached) {
            val bytes = ByteArrayOutputStream()
            var offset: Long? = 0
            var expired = dao.outputCacheExpired(runId)
            while (offset != null && !expired) {
                val requestedOffset = offset
                when (val result = withTimeout(15_000) { client.readArtifact(ArtifactReadRequest(ref, requestedOffset, 65536)) }) {
                    ArtifactReadResult.Expired -> { expired = true; bytes.reset() }
                    is ArtifactReadResult.Unavailable -> error("Output segment unavailable")
                    is ArtifactReadResult.Chunk -> {
                        bytes.write(result.bytes.toByteArray())
                        check(bytes.size() <= 512 * 1024) { "Output segment too large" }
                        check(result.nextOffset?.let { it > requestedOffset } ?: true) { "Non advancing output cursor" }
                        offset = result.nextOffset
                    }
                }
            }
            db.withTransaction { cache.save(listOf(ChunkRow(ref.value, runId, bytes.toString("UTF-8"), expired))) }
        }
        db.withTransaction {
            dao.turnByRun(runId)?.takeIf { it.error == AppStrings.OUTPUT_SYNC_MARKER }?.let { row ->
                // A newer snapshot may contain output that this worker has not loaded yet.
                val latest = row.snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
                if (latest?.lastSequence == snapshot.lastSequence) dao.save(row.copy(error = null))
            }
        }
    }
}
