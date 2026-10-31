package com.github.ytlog.mobby.android.conversation.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputCacheQueryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ConversationDatabase::class.java)
        .allowMainThreadQueries().build()

    @After fun close() = db.close()

    /**
     * On the tablet mobby crashed at every start: the output cache query joined each turn's snapshot to every one of its
     * chunks before grouping, so a few long Pi runs (about 2000 chunks, 250 KB snapshots) made SQLite sort about 6 GB in
     * memory. The device's SQLite scans chunks first; Robolectric's 3.32 scans turns first and never built that sort, so
     * the crash was reproduced on a copy of the device database with SQLite 3.43 (6.09 GB peak before, 17 MB after, same
     * rows). This test keeps the memory cap Android effectively has and pins the results the cache relies on.
     */
    @Test fun `cache candidates read each snapshot once however many chunks a run has`() = runBlocking {
        val dao = db.dao()
        dao.save(ConversationRow("c", "{}", 0))
        val snapshot = "x".repeat(200 * 1024)
        for (turn in 0 until 3) {
            dao.save(TurnRow("t$turn", "c", "prompt", "{}", turn.toLong(), runId = "r$turn", snapshot = snapshot, pending = false, occupied = false))
            dao.chunks((0 until 2_000).map { ChunkRow("r$turn/$it", "r$turn", "ab", expired = it == 0) })
        }
        db.openHelper.writableDatabase.apply {
            query("PRAGMA temp_store=MEMORY").use { it.moveToFirst() }
            query("PRAGMA hard_heap_limit=${64 * 1024 * 1024}").use { it.moveToFirst() }
        }
        val page = dao.outputCacheCandidates(null, null, "", 64)
        assertEquals(listOf("t0", "t1", "t2"), page.map { it.turnId })
        // Expired chunks no longer count; every other chunk holds two bytes.
        assertEquals(listOf(3_998L, 3_998L, 3_998L), page.map { it.bytes })
        assertEquals(snapshot.length, page.first().snapshot.length)
        val after = dao.outputCacheCandidates(null, page.first().createdAt, page.first().turnId, 64)
        assertEquals(listOf("t1", "t2"), after.map { it.turnId })
    }
}
