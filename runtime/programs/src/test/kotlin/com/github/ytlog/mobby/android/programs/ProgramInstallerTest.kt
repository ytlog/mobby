package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.*
import com.github.ytlog.mobby.android.programs.store.*
import kotlinx.coroutines.runBlocking
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
class ProgramInstallerTest {
    private val root = Files.createTempDirectory("install").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()
    private val layout = ProgramLayout(File(root, "programs"))
    private val installer = ProgramInstaller(database, layout, ProgramPackageReader(fixtureRegistry())) { 100 }
    private val source = PackageBuilder(File(root, "workspace/price-watch"))

    @After fun close() { database.close(); root.deleteRecursively() }

    private fun grant(capability: String) = runBlocking { dao.grants("price-watch").single { it.capability == capability } }

    @Test fun `a new program is paused, grants nothing and runs from its own copy`() = runBlocking {
        assertEquals(InstallResult("price-watch", "1.0.0", updated = false), installer.install(source.write()))
        assertEquals(UserState.PAUSED.wire, dao.program("price-watch")!!.userState)
        val net = grant("net.http")
        assertFalse(net.enabled)
        assertEquals(Scope.fromJson(net.requestedScope), Scope.fromJson(net.grantedScope))
        assertEquals(listOf("price-watch/hourly"), dao.subscriptions("price-watch").map { it.subscriptionId })
        val copy = File(dao.version("price-watch", "1.0.0")!!.packagePath)
        File(source.write(), "main.js").writeText("tampered")
        assertEquals("module.exports = {}", File(copy, "main.js").readText())
    }

    @Test fun `updates keep the intersection of old grants and new requests and never widen`() = runBlocking {
        installer.install(source.write())
        dao.upsertGrant(grant("net.http").copy(enabled = true))
        dao.setUserState("price-watch", UserState.ENABLED.wire, 1)
        dao.setSubscriptionPaused("price-watch/hourly", true)
        source.version = "1.1.0"
        source.permissions = """[{"capability":"net.http","required":true,"scope":{"domains":["api.example.com","more.example.com"],"methods":["GET"]}},
            {"capability":"time.schedule","required":true},{"capability":"notify.show","required":false}]"""
        val preview = installer.preview(source.write())
        assertEquals("1.0.0", preview.installedVersion)
        // net.http asks for a new domain, notify.show is new, time.schedule was never enabled.
        assertEquals(listOf("net.http", "notify.show", "time.schedule"), preview.needsNewGrant.sorted())
        assertTrue(installer.install(source.write()).updated)
        val net = grant("net.http")
        assertTrue(net.enabled)
        assertEquals(setOf("api.example.com"), Scope.fromJson(net.grantedScope).domains)
        assertEquals(2L, net.revision)
        assertFalse(grant("notify.show").enabled)
        assertTrue(dao.grants("price-watch").none { it.capability == "storage.sql" })
        assertEquals(UserState.ENABLED.wire, dao.program("price-watch")!!.userState)
        assertTrue(dao.subscriptions("price-watch").single().paused)
    }

    @Test fun `config values survive only when key and type stay the same`() = runBlocking {
        installer.install(source.write())
        dao.upsertConfig(ConfigRow("price-watch", "threshold", "100", 1))
        dao.upsertSecret(SecretRow("price-watch", "apiToken", byteArrayOf(1), byteArrayOf(2), "api.example.com", 1, 1))
        source.version = "1.1.0"
        installer.install(source.write())
        assertEquals("100", dao.config("price-watch").single().valueJson)
        assertNotNull(dao.secret("price-watch", "apiToken"))
        source.version = "1.2.0"
        source.config = """[{"key":"threshold","type":"string","required":true,"label":"阈值"}]"""
        installer.install(source.write())
        assertTrue(dao.config("price-watch").isEmpty())
        assertNull(dao.secret("price-watch", "apiToken"))
    }

    @Test fun `runtime subscriptions and stale copies do not outlive an update`() = runBlocking {
        installer.install(source.write())
        dao.upsertSubscription(SubscriptionRow("price-watch/dynamic", "price-watch", "1.0.0", "time.schedule", "{}", "h", "runtime", false, null, 0))
        source.version = "1.1.0"; installer.install(source.write())
        source.version = "1.2.0"; installer.install(source.write())
        assertEquals(listOf("price-watch/hourly"), dao.subscriptions("price-watch").map { it.subscriptionId })
        assertEquals(setOf("1.1.0", "1.2.0"), layout.packages("price-watch").listFiles()!!.map { it.name.substringBefore('-') }.toSet())
    }

    @Test fun `delete keeps private data unless asked`() = runBlocking {
        installer.install(source.write())
        layout.files("price-watch").mkdirs()
        File(layout.files("price-watch"), "kept.txt").writeText("x")
        installer.delete("price-watch", withData = false)
        assertNull(dao.program("price-watch"))
        assertFalse(layout.packages("price-watch").exists())
        assertTrue(File(layout.files("price-watch"), "kept.txt").exists())
        installer.install(source.write())
        installer.delete("price-watch", withData = true)
        assertFalse(layout.data("price-watch").exists())
        assertTrue(File(root, "workspace/price-watch/main.js").exists())
    }
}
