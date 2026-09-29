package com.github.ytlog.mobby.android.conversation.data

import com.github.ytlog.mobby.android.localization.AppStrings

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only terminal output copies are disposable; user input and conversation metadata are retained. */
internal class OutputCache(private val db: ConversationDatabase, private val client: RuntimeClient,
    private val maxBytes: Long = 256L * 1024 * 1024) {
    companion object { const val VERIFICATION_WARNING = AppStrings.CACHE_VERIFICATION_MARKER }
    init { require(maxBytes >= 0) }
    private val dao = db.dao()
    private val reconciliation = Mutex()
    private fun OutputCacheCandidate.eligible(): Boolean {
        val state = storageJson.decodeFromString<RunSnapshot>(snapshot)
        return state.runId.value == runId && state.phase.terminal && !RunStateRules.occupiesExecution(state)
    }

    private suspend fun candidates(conversationId: String? = null, consume: suspend (OutputCacheCandidate) -> Boolean) {
        var time: Long? = null
        var id = ""
        while (true) {
            val page = dao.outputCacheCandidates(conversationId, time, id, 64)
            if (page.isEmpty()) return
            for (candidate in page) if (candidate.eligible() && !consume(candidate)) return
            time = page.last().createdAt
            id = page.last().turnId
        }
    }

    /** Caller holds the projection transaction; a delayed download cannot overwrite a tombstone. */
    suspend fun save(rows: List<ChunkRow>) {
        for (row in rows) {
            val old = dao.chunk(row.ref)
            dao.chunks(listOf(if (dao.outputCacheExpired(row.runId) || old?.expired == true) row.copy(text = "", expired = true) else row))
        }
    }

    suspend fun compact() = db.withTransaction {
        var bytes = 0L
        candidates { bytes += it.bytes; true }
        if (bytes <= maxBytes) return@withTransaction
        candidates { candidate ->
            dao.expire(ExpiredOutputCacheRow(candidate.runId))
            dao.expireOutputCache(candidate.runId)
            dao.turn(candidate.turnId)?.takeIf { it.error == VERIFICATION_WARNING }?.let { dao.save(it.copy(error = null)) }
            bytes -= candidate.bytes
            bytes > maxBytes
        }
    }

    suspend fun expire(ref: String) = db.withTransaction {
        val chunk = dao.chunk(ref) ?: return@withTransaction
        dao.expireChunk(ref)
        if (dao.availableChunkRefs(chunk.runId).isEmpty()) {
            dao.turnByRun(chunk.runId)?.takeIf { it.error == VERIFICATION_WARNING }?.let { dao.save(it.copy(error = null)) }
        }
    }

    /** Runtime I/O stays outside Room transactions. Only explicit expiration discards a cached copy. */
    suspend fun reconcile(conversationId: String? = null) = reconciliation.withLock {
        candidates(conversationId) { candidate ->
            val unavailableRefs = mutableListOf<String>()
            for (ref in dao.availableChunkRefs(candidate.runId)) {
                when (client.readArtifact(ArtifactReadRequest(ResourceRef(ref), 0, 1))) {
                    ArtifactReadResult.Expired -> expire(ref)
                    is ArtifactReadResult.Unavailable -> unavailableRefs += ref
                    is ArtifactReadResult.Chunk -> Unit
                }
            }
            db.withTransaction {
                val unavailable = unavailableRefs.any { dao.chunkAvailable(it) }
                dao.turn(candidate.turnId)?.let { row ->
                    if (unavailable && row.error == null) dao.save(row.copy(error = VERIFICATION_WARNING))
                    else if (!unavailable && row.error == VERIFICATION_WARNING) dao.save(row.copy(error = null))
                }
            }
            true
        }
    }
}
