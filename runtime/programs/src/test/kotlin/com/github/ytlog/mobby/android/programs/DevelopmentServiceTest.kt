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
    /** What the stand-in runtime says about script syntax; null means the runtime cannot check. */
    private var syntax: ((List<String>) -> List<SyntaxProblem>)? = { emptyList() }
    private val checked = mutableListOf<String>()
    /** Extra steps the stand-in runtime takes after its calls, such as printing output or posting to the page. */
    private var afterCalls: (ProcessSpec) -> Unit = {}

    private inner class ScriptedLauncher : ProgramProcessLauncher {
        override fun checkSyntax(sdkDir: File, packageDir: File, scripts: List<String>): List<SyntaxProblem> {
            checked += scripts
            return syntax?.invoke(scripts) ?: throw LaunchUnavailable("runtime_not_installed")
        }

        override fun launch(spec: ProcessSpec): ProgramProcess {
            val exit = CompletableDeferred<Int?>()
            scope.launch {
                val context = platform.runs.resolve(spec.credential)!!
                platform.runs.attach(context.runId!!)!!.first()
                platform.pipeline.call(context, CallRequest("r1", "notes.write", JsonObject(emptyMap())))
                platform.pipeline.call(context, CallRequest("r2", "storage.kv", buildJsonObject { put("op", "set"); put("key", "k"); put("value", 1) }))
                platform.pipeline.call(context, CallRequest("r3", "log", buildJsonObject { put("message", "done") }))
                afterCalls(spec)
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

    /** On the tablet a page module with a syntax error passed validate and then never ran. */
    @Test fun `validate parses every script and reports those that do not parse`() {
        platform = platform()
        val dev = DevelopmentService(platform)
        write().also { File(it, "ui").mkdirs(); File(it, "ui/app.js").writeText("x"); File(it, "ui/style.css").writeText("") }
        syntax = { scripts -> scripts.filter { it == "ui/app.js" }.map { SyntaxProblem(it, 3, "SyntaxError: Unexpected reserved word") } }
        val result = dev.validate("price-watch")
        assertEquals(listOf("main.js", "ui/app.js"), checked.sorted())
        assertFalse(result["valid"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("ui/app.js:3: SyntaxError: Unexpected reserved word"), result["problems"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `validate says when scripts could not be checked instead of passing them silently`() {
        platform = platform()
        write()
        syntax = null
        val result = DevelopmentService(platform).validate("price-watch")
        assertTrue(result["valid"]!!.jsonPrimitive.boolean)
        assertTrue(result["warnings"]!!.jsonArray.any { "not syntax-checked" in it.jsonPrimitive.content && "runtime_not_installed" in it.jsonPrimitive.content })
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

    /**
     * On the tablet the page's export handler was never dry-run and failed on first use; a dry run's `ui.post` also went to
     * the installed program's open page.
     */
    @Test fun `a dry run keeps page messages to itself and names the handlers it did not exercise`() = runBlocking {
        platform = platform()
        platform.start()
        write()
        afterCalls = { spec ->
            File(root, "programs/logs/${spec.runId}.log").apply { parentFile!!.mkdirs() }
                .writeText("[out] loading\n[out] mobby:handlers [\"onSchedule\",\"ui.message\",\"onExport\"]\n")
            val connection = java.net.URL("http://127.0.0.1:${spec.apiPort}/v1/ui/messages").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${spec.credential}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"message":{"type":"exported"}}""".toByteArray()) }
            val reply = Json.parseToJsonElement(connection.inputStream.use { it.readBytes().decodeToString() }).jsonObject
            check(reply["simulated"]?.jsonPrimitive?.boolean == true && reply["delivered"]!!.jsonPrimitive.int == 0) { "page message was not simulated: $reply" }
        }
        val report = DevelopmentService(platform).dryRun("price-watch", """[{"handler":"onSchedule","type":"time.schedule","payload":{}}]""", 10_000, emptyMap())
        assertTrue(report.toString(), report["ran"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(buildJsonObject { put("type", "exported") }), report["uiMessages"]!!.jsonArray.toList())
        assertEquals(listOf("done"), report["logs"]!!.jsonArray.map { it.jsonObject["message"]!!.jsonPrimitive.content })
        assertEquals(listOf("onSchedule", "ui.message", "onExport"), report["handlers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("ui.message", "onExport"), report["notExercised"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /**
     * On the tablet the AI requested entry.share and entry.shortcut but left `subscriptions` empty; validate and both dry runs
     * passed, yet no real share or tap would ever have reached the handlers.
     */
    @Test fun `validate and dry runs say when an event handler has no subscription to deliver it`() = runBlocking {
        platform = platform()
        platform.start()
        val dev = DevelopmentService(platform)
        PackageBuilder(File(workspace, "price-watch")).apply {
            ui = null; config = "[]"; subscriptions = "[]"
            permissions = """[{"capability":"notes.write","required":true},{"capability":"storage.kv","required":true},{"capability":"time.schedule","required":true}]"""
        }.write()
        val warnings = dev.validate("price-watch")["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.toString(), warnings.any { "time.schedule" in it && "no subscription" in it })
        val report = dev.dryRun("price-watch", """[{"handler":"onSchedule","type":"time.schedule","payload":{}},{"handler":"ui.message","type":"ui.message","payload":{}}]""",
            10_000, emptyMap())
        val unsubscribed = report["unsubscribed"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, unsubscribed.size)
        assertTrue(unsubscribed.single(), "time.schedule" in unsubscribed.single() && "onSchedule" in unsubscribed.single())
        write()
        assertTrue(dev.validate("price-watch")["warnings"]!!.jsonArray.none { "no subscription" in it.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), dev.dryRun("price-watch", """[{"handler":"onSchedule","type":"time.schedule","payload":{}}]""", 10_000, emptyMap())["unsubscribed"])
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
        assertTrue("page errors", catalog.contains("parses every script") && catalog.contains("`page_error`"))
        // The phone AI dry-ran the schedule handler only; page messages reach the background as the `ui.message` handler.
        assertTrue("dry-run page messages", catalog.contains("""{"handler": "ui.message", "payload": {"message": {"type": "export"}}}""") &&
            catalog.contains("`notExercised`") && catalog.contains("`uiMessages`"))
        assertTrue("argument errors", catalog.contains("`TypeError`"))
    }
}
