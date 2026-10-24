package com.github.ytlog.mobby.android.programs

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.store.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProgramsDatabaseTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()

    @After fun close() = database.close()

    private fun operation(id: String, caller: String, requestId: String, at: Long = 1) =
        OperationRow(id, "p", caller, requestId, "f", "storage.kv", 1, at, status = "accepted", effect = "none")

    @Test fun `request ids are unique per caller, not globally`() = runBlocking {
        dao.insertOperation(operation("a", "run:1", "r-1"))
        dao.insertOperation(operation("b", "run:2", "r-1"))
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { dao.insertOperation(operation("c", "run:1", "r-1")) } }
        assertEquals("b", dao.operation("run:2", "r-1")!!.operationId)
    }

    @Test fun `deleting a program removes its records and keeps private data unless asked`() = runBlocking {
        dao.upsertProgram(ProgramRow("p", "P", "paused", "1", 0, 0))
        dao.upsertGrant(GrantRow("p", "net.http", "{}", "{}", true, true, "manifest", 0, null, 1))
        dao.upsertSubscription(SubscriptionRow("p/s", "p", "1", "time.schedule", "{}", "h", "manifest", false, null, 0))
        dao.upsertKv(KvRow("p", "k", "v", 0))
        dao.insertOperation(operation("a", "run:1", "r"))
        dao.deleteProgram("p", withData = false)
        assertNull(dao.program("p"))
        assertTrue(dao.grants("p").isEmpty() && dao.subscriptions("p").isEmpty() && dao.operations("p", 10).isEmpty())
        assertEquals("v", dao.kv("p", "k")!!.value)
        dao.deleteProgram("p", withData = true)
        assertNull(dao.kv("p", "k"))
    }

    @Test fun `key value usage and pruning are bounded queries`() = runBlocking {
        dao.upsertKv(KvRow("p", "ab", "cde", 0))
        dao.upsertKv(KvRow("p", "x", "y", 0))
        assertEquals(7L, dao.kvBytes("p"))
        (1..5).forEach { dao.insertOperation(operation("o$it", "run:1", "r$it", at = it.toLong())) }
        dao.pruneOperations(keep = 3, before = 0)
        assertEquals(listOf("o5", "o4", "o3"), dao.operations("p", 10).map { it.operationId })
        dao.pruneOperations(keep = 10, before = 5)
        assertEquals(listOf("o5"), dao.operations("p", 10).map { it.operationId })
    }

    @Test fun `the log view follows logs written after it opened`() = runBlocking {
        val logs = dao.logsFlow("p", 10)
        dao.insertLog(LogRow(programId = "p", caller = "run:1", at = 1, message = "first", dataJson = null))
        assertEquals(listOf("first"), withTimeout(5_000) { logs.first { it.isNotEmpty() } }.map { it.message })
        dao.insertLog(LogRow(programId = "p", caller = "run:2", at = 2, message = "second", dataJson = null))
        assertEquals(listOf("second", "first"), withTimeout(5_000) { logs.first { it.size == 2 } }.map { it.message })
    }
}
