package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.base.storage.KvCapability
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.dev.DevelopmentService
import com.github.ytlog.mobby.android.programs.event.ScheduleBackend
import com.github.ytlog.mobby.android.programs.event.ScheduleCapability
import com.github.ytlog.mobby.android.programs.event.ScheduleSpec
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.run.*
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class DevelopmentServiceTest {
    private val root = Files.createTempDirectory("dev").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workspace = File(root, "workspace/programs").apply { mkdirs() }
    private val notes = FakeHandler("notes.write")
    private lateinit var platform: ProgramPlatform

    /** Stands in for Node: makes one command call and one storage call through the real pipeline, then exits. */
    private inner class ScriptedLauncher : ProgramProcessLauncher {
        override fun launch(spec: ProcessSpec): ProgramProcess {
            val exit = CompletableDeferred<Int?>()
            scope.launch {
                val context = platform.runs.resolve(spec.credential)!!
                platform.runs.attach(context.runId!!)!!.first()
                platform.pipeline.call(context, CallRequest("r1", "notes.write", JsonObject(emptyMap())))
                platform.pipeline.call(context, CallRequest("r2", "storage.kv", buildJsonObject { put("op", "set"); put("key", "k"); put("value", 1) }))
                platform.pipeline.call(context, CallRequest("r3", "log", buildJsonObject { put("message", "done") }))
                exit.complete(0)
            }
            return object : ProgramProcess {
                override val pid = 1
                override suspend fun awaitExit() = exit.await()
                override fun terminate() { exit.complete(143) }
            }
        }
    }

    private fun platform(): ProgramPlatform {
        val dao = database.dao()
        val registry = CapabilityRegistry(listOf(notes, KvCapability(dao, { 1 }), com.github.ytlog.mobby.android.programs.base.log.LogCapability(dao, { 1 }),
            FakeHandler("net.http", scope = ScopeKind.DOMAINS), FakeHandler("storage.sql"), ScheduleCapability()))
        return ProgramPlatform(ApplicationProvider.getApplicationContext(), database, ProgramLayout(File(root, "programs")), workspace,
            { ScriptedLauncher() }, registry, SecretStore(dao, object : SecretCipher {
                override fun encrypt(plain: ByteArray) = plain to byteArrayOf(0)
                override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext
            }) { 1 }, object : ScheduleBackend { override fun sync(schedules: List<ScheduleSpec>) = Unit }, null, scope)
    }

    @After fun close() { scope.cancel(); runCatching { platform.stopServer() }; database.close(); root.deleteRecursively() }

    private fun write(name: String = "price-watch", main: String = "module.exports = {}") = PackageBuilder(File(workspace, name)).apply {
        ui = null; config = "[]"
        permissions = """[{"capability":"notes.write","required":true},{"capability":"storage.kv","required":true},{"capability":"time.schedule","required":true}]"""
        files["main.js"] = main
    }.write()

    @Test fun `paths must be program folders directly under the workspace`() {
        platform = platform()
        val dev = DevelopmentService(platform)
        write()
        assertEquals(File(workspace, "price-watch").canonicalFile, dev.resolve("~/workspace/programs/price-watch").canonicalFile)
        assertEquals(File(workspace, "price-watch").canonicalFile, dev.resolve("price-watch").canonicalFile)
        listOf("../secrets", "/etc", "price-watch/main.js", "missing").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { dev.resolve(path) }
        }
    }

    @Test fun `validate lists problems and points out direct Node access`() {
        platform = platform()
        val dev = DevelopmentService(platform)
        write(main = "const mobby = require('mobby'); mobby.net.fetch('x'); require('node:fs'); fetch('https://evil')")
        val ok = dev.validate("price-watch")
        assertTrue(ok["valid"]!!.jsonPrimitive.boolean)
        val warnings = ok["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { "file system" in it } && warnings.any { "global fetch" in it })
        File(workspace, "price-watch/manifest.json").writeText("{}")
        assertFalse(dev.validate("price-watch")["valid"]!!.jsonPrimitive.boolean)
    }

    @Test fun `a dry run simulates effects, keeps storage throwaway and reports calls`() = runBlocking {
        platform = platform()
        platform.start()
        val dev = DevelopmentService(platform)
        write()
        val report = dev.dryRun("price-watch", """[{"handler":"onSchedule","type":"time.schedule","payload":{}}]""", 10_000, emptyMap())
        assertTrue(report.toString(), report["ran"]!!.jsonPrimitive.boolean)
        val calls = report["calls"]!!.jsonArray.map { it.jsonObject["capability"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.content }
        assertEquals(listOf("notes.write" to "simulated", "storage.kv" to "succeeded", "log" to "succeeded"), calls)
        assertTrue(notes.calls.isEmpty())
        assertEquals("done", report["logs"]!!.jsonArray.single().jsonObject["message"]!!.jsonPrimitive.content)
        assertFalse(File(root, "programs/data/price-watch/dry-run").listFiles().orEmpty().any { it.isDirectory })
        assertNull(database.dao().kv("price-watch", "k"))
        val records = dev.records("price-watch", 10)
        assertTrue(records["runs"]!!.jsonArray.single().jsonObject["dryRun"]!!.jsonPrimitive.boolean)
    }

    @Test fun `the catalog lists only registered capabilities`() {
        platform = platform()
        val catalog = DevelopmentService(platform).catalog()
        assertTrue(catalog.contains("### notes.write") && catalog.contains("### time.schedule"))
        assertFalse(catalog.contains("### screen.click"))
    }

    @Test fun `the guide states result shapes the AI cannot infer`() {
        platform = platform()
        val catalog = DevelopmentService(platform).catalog()
        assertTrue("SQL rows are arrays", catalog.contains("`{columns, rows}`") && catalog.contains("each row is an array"))
        assertTrue("simulated fetch", catalog.contains("`response.simulated`") && catalog.contains("`SIMULATED`"))
        // The phone AI called files.write({path, text}); arguments are positional.
        assertTrue("positional signatures", catalog.contains("`files.write(path, text)`") && catalog.contains("positional"))
        // The phone AI's page said "Exported" because ui.send resolved before the failing handler ran.
        assertTrue("ui.send delivery", catalog.contains("not when its handler finishes") && catalog.contains("exit code 1"))
    }
}
