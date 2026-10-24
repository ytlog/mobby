package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.storage.*
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.DryRunScope
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StorageTest {
    private val root = Files.createTempDirectory("storage").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val limits = StorageLimits(kvBytes = 200, kvValueBytes = 100, filesBytes = 1_000, fileReadBytes = 500, sqlRows = 5)
    private val kv = KvCapability(database.dao(), { 1 }, limits)
    private val files = FilesCapability(limits)
    private val sql = SqlCapability(limits)
    private val space = ProgramSpace("p", File(root, "data/p/files").apply { mkdirs() }, File(root, "data/p/tmp").apply { mkdirs() })
    private val dryContext = CallContext("p", "1", Surface.BACKGROUND, runId = "dry", dryRun = DryRunScope(emptySet()))
    private val drySpace = ProgramSpace("p", File(root, "data/p/dry-run/dry/files").apply { mkdirs() }, File(root, "data/p/dry-run/dry/tmp"))

    @After fun close() { sql.close(root); database.close(); root.deleteRecursively() }

    private fun run(handler: CapabilityHandler, args: String, context: CallContext = CallContext("p", "1", Surface.BACKGROUND, runId = "r"),
        where: ProgramSpace = space) = runBlocking { handler.invoke(CapabilityCall(context, Json.parseToJsonElement(args).jsonObject, where) { false }) }
    private fun failure(handler: CapabilityHandler, args: String, where: ProgramSpace = space) =
        runCatching { run(handler, args, where = where) }.exceptionOrNull() as CapabilityException

    @Test fun `key value storage keeps JSON values within its quota and dry runs apart`() {
        run(kv, """{"op":"set","key":"last","value":{"price":98}}""")
        assertEquals(buildJsonObject { put("price", 98) }, run(kv, """{"op":"get","key":"last"}""").result)
        assertEquals(JsonNull, run(kv, """{"op":"get","key":"last"}""", dryContext).result)
        assertEquals("value_too_large", failure(kv, """{"op":"set","key":"big","value":"${"x".repeat(120)}"}""").reason)
        run(kv, """{"op":"set","key":"a","value":"${"x".repeat(90)}"}""")
        assertEquals(ProgramErrorCode.QUOTA_EXCEEDED, failure(kv, """{"op":"set","key":"b","value":"${"x".repeat(90)}"}""").code)
        assertEquals(Effect.CONFIRMED, run(kv, """{"op":"delete","key":"last"}""").effect)
        assertEquals(Effect.NONE, run(kv, """{"op":"delete","key":"last"}""").effect)
    }

    @Test fun `files stay inside the program directory`() {
        run(files, """{"op":"write","path":"reports/today.json","text":"{\"ok\":true}"}""")
        assertEquals(JsonPrimitive("{\"ok\":true}"), run(files, """{"op":"read","path":"reports/today.json"}""").result)
        assertEquals("reports", run(files, """{"op":"list"}""").result.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        listOf("../escape.txt", "/etc/hosts", "reports/../../x", "./a").forEach { path ->
            assertEquals(path, ProgramErrorCode.INVALID_ARGUMENT, failure(files, """{"op":"read","path":"$path"}""").code)
        }
        Files.createSymbolicLink(File(space.files, "link").toPath(), root.toPath())
        assertEquals("links_not_allowed", failure(files, """{"op":"read","path":"link/data/p/files/reports/today.json"}""").reason)
        File(space.files, "binary.bin").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals("file_is_not_utf8_text", failure(files, """{"op":"read","path":"binary.bin"}""").reason)
        File(space.files, "binary.bin").delete()
        run(files, """{"op":"write","path":"big1.txt","text":"${"x".repeat(480)}"}""")
        run(files, """{"op":"write","path":"big2.txt","text":"${"x".repeat(480)}"}""")
        assertEquals(ProgramErrorCode.QUOTA_EXCEEDED, failure(files, """{"op":"write","path":"big3.txt","text":"${"x".repeat(480)}"}""").code)
        assertEquals(Effect.CONFIRMED, run(files, """{"op":"delete","path":"reports"}""").effect)
        assertEquals(ProgramErrorCode.RESOURCE_MISSING, failure(files, """{"op":"read","path":"reports/today.json"}""").code)
    }

    @Test fun `sql binds typed parameters and returns typed rows`() {
        run(sql, """{"op":"execute","sql":"CREATE TABLE price(at INTEGER, value REAL, note TEXT)"}""")
        val inserted = run(sql, """{"op":"execute","sql":"INSERT INTO price VALUES (?, ?, ?)","params":[1, 98.5, "low"]}""").result.jsonObject
        assertEquals(1L, inserted["lastInsertRowId"]!!.jsonPrimitive.long)
        run(sql, """{"op":"execute","sql":"INSERT INTO price VALUES (?, ?, ?)","params":[2, 120, null]}""")
        val rows = run(sql, """{"op":"query","sql":"SELECT at, value, note FROM price WHERE value < ? ORDER BY at","params":[100]}""").result.jsonObject
        assertEquals(listOf("at", "value", "note"), rows["columns"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(listOf(JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(98.5), JsonPrimitive("low"))))), rows["rows"])
        assertEquals(2, run(sql, """{"op":"execute","sql":"UPDATE price SET note = 'x'"}""").result.jsonObject["changes"]!!.jsonPrimitive.int)
    }

    @Test fun `transactions are atomic`() {
        run(sql, """{"op":"execute","sql":"CREATE TABLE t(id INTEGER PRIMARY KEY)"}""")
        failure(sql, """{"op":"transaction","statements":[{"sql":"INSERT INTO t VALUES (1)"},{"sql":"INSERT INTO t VALUES (1)"}]}""")
        assertEquals(0, run(sql, """{"op":"query","sql":"SELECT * FROM t"}""").result.jsonObject["rows"]!!.jsonArray.size)
        run(sql, """{"op":"transaction","statements":[{"sql":"INSERT INTO t VALUES (?)","params":[1]},{"sql":"INSERT INTO t VALUES (?)","params":[2]}]}""")
        assertEquals(2, run(sql, """{"op":"query","sql":"SELECT * FROM t"}""").result.jsonObject["rows"]!!.jsonArray.size)
    }

    @Test fun `statements that reach other files or chain are refused`() {
        listOf(
            "ATTACH DATABASE '/data/x.db' AS x" to "attach_not_allowed",
            "SELECT 1; DROP TABLE t" to "one_statement_per_call",
            "VACUUM INTO '/sdcard/copy.db'" to "vacuum_into_not_allowed",
            "SELECT load_extension('x')" to "load_extension_not_allowed",
            "PRAGMA writable_schema = ON" to "pragma_not_allowed",
            "SELECT 'unterminated" to "unterminated_literal_or_comment",
        ).forEach { (statement, reason) -> assertEquals(statement, reason, SqlGuard.problem(statement)) }
        assertNull(SqlGuard.problem("SELECT 'ATTACH; x' AS note; "))
        assertNull(SqlGuard.problem("PRAGMA user_version"))
        assertEquals("attach_not_allowed", failure(sql, """{"op":"execute","sql":"ATTACH DATABASE 'x.db' AS x"}""").reason)
    }

    @Test fun `large results are refused and dry runs get their own database`() {
        run(sql, """{"op":"execute","sql":"CREATE TABLE n(v INTEGER)"}""")
        repeat(6) { run(sql, """{"op":"execute","sql":"INSERT INTO n VALUES ($it)"}""") }
        assertEquals("result_has_more_than_5_rows", failure(sql, """{"op":"query","sql":"SELECT * FROM n"}""").reason)
        assertTrue(failure(sql, """{"op":"query","sql":"SELECT * FROM n"}""", where = drySpace).reason.startsWith("sql_error"))
        assertTrue(File(root, "data/p/dry-run/dry/sql/main.db").isFile)
    }
}
