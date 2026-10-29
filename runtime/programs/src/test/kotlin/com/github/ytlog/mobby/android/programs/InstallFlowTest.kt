package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.event.ScheduleBackend
import com.github.ytlog.mobby.android.programs.event.ScheduleSpec
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.*
import com.github.ytlog.mobby.android.programs.run.*
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
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
class InstallFlowTest {
    private val root = Files.createTempDirectory("install-flow").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val secrets = SecretStore(database.dao(), object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain to byteArrayOf(0)
        override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext
    }) { 1L }
    private val platform = ProgramPlatform(ApplicationProvider.getApplicationContext(), database, ProgramLayout(File(root, "programs")),
        File(root, "workspace/programs"), { object : ProgramProcessLauncher {
            override fun launch(spec: ProcessSpec): ProgramProcess = error("no runs in these tests")
        } }, fixtureRegistry(), secrets, object : ScheduleBackend { override fun sync(schedules: List<ScheduleSpec>) {} }, null, scope) { 1_000L }
    private val source = PackageBuilder(File(root, "workspace/programs/price-watch")).apply {
        permissions = """[{"capability":"net.http","required":true,"scope":{"domains":["api.example.com"],"methods":["GET"]}},
            {"capability":"storage.sql","required":true},{"capability":"time.schedule","required":true},
            {"capability":"notify.show","required":false}]"""
    }

    @After fun close() { scope.cancel(); runCatching { platform.stopServer() }; database.close(); root.deleteRecursively() }

    private fun program() = runBlocking { platform.programs.first().single() }
    private fun ready() = runBlocking { platform.pendingInstalls().single() as PendingInstall.Ready }
    private fun choice(preview: InstallPreview, grant: Set<String>, enable: Boolean = true) = InstallChoice(preview.pkg.checksum, grant,
        mapOf("threshold" to JsonPrimitive(5)), mapOf("apiToken" to "test-token"), enable)

    @Test fun `workspace programs are pending until installed as written`() = runBlocking {
        platform.start()
        source.write()
        val first = ready()
        assertNull(first.preview.installedVersion)
        assertEquals(listOf("net.http", "notify.show", "storage.sql", "time.schedule"), first.preview.newlyRequested.sorted())
        platform.install(source.write())
        assertTrue(platform.pendingInstalls().isEmpty())
        // The AI often changes code without bumping the version; that is still an update.
        source.files["main.js"] = "module.exports = { changed: true }"
        source.write()
        val update = ready()
        assertEquals("1.0.0", update.preview.installedVersion)
        assertTrue(update.preview.newlyRequested.isEmpty())
    }

    @Test fun `a package that fails validation is listed with its problems`() = runBlocking {
        source.extra = "\"unknownField\":1"
        source.write()
        val rejected = platform.pendingInstalls().single() as PendingInstall.Rejected
        assertEquals("price-watch", rejected.candidate.name)
        assertTrue(rejected.problems.isNotEmpty())
    }

    /** On the tablet a deleted program came straight back under pending, because its source stays in the workspace. */
    @Test fun `a deleted program stays off the pending list until its source changes`() = runBlocking {
        platform.start()
        source.write()
        platform.install(source.directory(), choice(ready().preview, setOf("net.http", "storage.sql", "time.schedule")))
        platform.delete("price-watch", withData = true)
        assertTrue(platform.pendingInstalls().isEmpty())
        source.files["main.js"] = "module.exports = { changed: true }"
        source.write()
        assertNull(ready().preview.installedVersion)
    }

    @Test fun `a dismissed version is hidden until the AI changes it`() = runBlocking {
        platform.start()
        source.extra = "\"unknownField\":1"
        source.write()
        platform.dismiss(platform.pendingInstalls().single().candidate)
        assertTrue(platform.pendingInstalls().isEmpty())
        source.extra = ""
        source.write()
        val fixed = ready()
        platform.dismiss(fixed.candidate)
        assertTrue(platform.pendingInstalls().isEmpty())
        // Installing a later version forgets the dismissal, so going back to the dismissed code is an update again.
        val original = source.files.getValue("main.js")
        source.files["main.js"] = "module.exports = { changed: true }"
        source.write()
        platform.install(source.directory(), choice(ready().preview, setOf("net.http", "storage.sql", "time.schedule")))
        source.files["main.js"] = original
        source.write()
        assertEquals("1.0.0", ready().preview.installedVersion)
    }

    @Test fun `one confirmation installs, grants the chosen capabilities, configures and enables`() = runBlocking {
        platform.start()
        source.write()
        val preview = ready().preview
        platform.install(source.directory(), choice(preview, setOf("net.http", "storage.sql", "time.schedule")))
        val installed = program()
        assertEquals(UserState.ENABLED, installed.userState)
        assertEquals(ProgramAvailability.WORKING, installed.availability)
        val granted = installed.permissions.associateBy { it.capability }
        assertEquals(setOf("api.example.com"), granted.getValue("net.http").granted.domains)
        assertTrue(granted.getValue("net.http").enabled)
        // An optional capability the user left unticked stays off.
        assertFalse(granted.getValue("notify.show").enabled)
        assertTrue(installed.config.single { it.field.key == "apiToken" }.secretSet)
        assertEquals(JsonPrimitive(5), installed.config.single { it.field.key == "threshold" }.value)
    }

    @Test fun `a package changed after review installs nothing`() = runBlocking {
        platform.start()
        source.write()
        val preview = ready().preview
        source.files["main.js"] = "module.exports = { other: true }"
        source.write()
        assertThrows(PackageChanged::class.java) { runBlocking { platform.install(source.directory(), choice(preview, setOf("net.http"))) } }
        assertTrue(platform.programs.first().isEmpty())
    }

    @Test fun `invalid settings are refused before anything is installed`() = runBlocking {
        platform.start()
        source.write()
        val preview = ready().preview
        val bad = choice(preview, emptySet()).copy(values = mapOf("threshold" to JsonPrimitive("many")))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { platform.install(source.directory(), bad) } }
        assertTrue(platform.programs.first().isEmpty())
    }

    @Test fun `an update keeps the run state and grants only what was confirmed`() = runBlocking {
        platform.start()
        source.write()
        platform.install(source.directory(), choice(ready().preview, setOf("net.http", "storage.sql", "time.schedule")))
        platform.setEnabled("price-watch", false)
        source.permissions = source.permissions.replace("\"notify.show\",\"required\":false}", "\"notify.show\",\"required\":false},{\"capability\":\"screen.click\",\"required\":false,\"scope\":{\"packages\":[\"com.example\"]}}")
        source.write()
        val preview = ready().preview
        assertEquals(listOf("screen.click"), preview.newlyRequested)
        platform.install(source.directory(), InstallChoice(preview.pkg.checksum, setOf("screen.click"), enable = false))
        val updated = program()
        assertEquals(UserState.PAUSED, updated.userState)
        val granted = updated.permissions.associateBy { it.capability }
        assertTrue(granted.getValue("screen.click").enabled)
        assertEquals(Scope(packages = setOf("com.example")), granted.getValue("screen.click").granted)
        assertFalse(granted.getValue("notify.show").enabled)
        assertTrue(updated.config.single { it.field.key == "apiToken" }.secretSet)
    }
}
