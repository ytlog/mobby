package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.event.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EventRoutingTest {
    private val root = Files.createTempDirectory("routing").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private var now = 1_000_000L

    private class FakeSchedules : ScheduleBackend {
        var synced: List<ScheduleSpec> = emptyList()
        override fun sync(schedules: List<ScheduleSpec>) { synced = schedules }
    }

    private class Process : ProgramProcess {
        val exit = CompletableDeferred<Int?>()
        override val pid = 1
        override suspend fun awaitExit() = exit.await()
        override fun terminate() { exit.complete(143) }
    }

    private val launched = mutableListOf<ProcessSpec>()
    private val processes = mutableListOf<Process>()
    private val schedules = FakeSchedules()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val platform = ProgramPlatform(ApplicationProvider.getApplicationContext(), database, ProgramLayout(File(root, "programs")),
        File(root, "workspace/programs"), { object : ProgramProcessLauncher {
            override fun launch(spec: ProcessSpec): ProgramProcess { launched += spec; return Process().also { processes += it } }
        } }, CapabilityRegistry(listOf(FakeHandler("net.http", scope = ScopeKind.DOMAINS), FakeHandler("storage.sql"), ScheduleCapability())),
        com.github.ytlog.mobby.android.programs.base.secrets.SecretStore(database.dao(), object : com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher {
            override fun encrypt(plain: ByteArray) = plain to byteArrayOf(0)
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext
        }) { now }, schedules, null, scope) { now }

    @After fun close() { scope.cancel(); runCatching { platform.stopServer() }; database.close(); root.deleteRecursively() }

    private fun install() = runBlocking {
        platform.start()
        platform.install(PackageBuilder(File(root, "workspace/programs/price-watch")).apply {
            config = """[{"key":"threshold","type":"number","required":true,"label":"阈值"}]"""
            ui = null
        }.write())
    }
    private fun program() = runBlocking { platform.programs.first().single() }
    private fun ready() = runBlocking {
        platform.setEnabled("price-watch", true)
        for (capability in listOf("net.http", "storage.sql", "time.schedule")) platform.setGrant("price-watch", capability, true, null, null)
        platform.setConfig("price-watch", "threshold", JsonPrimitive(100))
    }

    @Test fun `a program receives events only once it is enabled, granted and configured`() = runBlocking {
        install()
        assertEquals(ProgramAvailability.PAUSED, program().availability)
        assertTrue(schedules.synced.isEmpty())
        platform.setEnabled("price-watch", true)
        assertEquals(ProgramAvailability.MISSING_PROGRAM_PERMISSION, program().availability)
        for (capability in listOf("net.http", "storage.sql", "time.schedule")) platform.setGrant("price-watch", capability, true, null, null)
        assertEquals(ProgramAvailability.NEEDS_CONFIG, program().availability)
        assertEquals(FireResult.INACTIVE, platform.router.fireSchedule("price-watch/hourly", null))
        platform.setConfig("price-watch", "threshold", JsonPrimitive(100))
        assertEquals(ProgramAvailability.WORKING, program().availability)
        assertEquals(listOf("price-watch/hourly"), schedules.synced.map { it.subscriptionId })
        assertEquals(3_600_000L, schedules.synced.single().everyMillis)
        assertTrue(program().subscriptions.single().active)
    }

    @Test fun `a fired schedule starts the background run with the subscription's handler`() = runBlocking {
        install(); ready()
        assertEquals(FireResult.DELIVERED, platform.router.fireSchedule("price-watch/hourly", now))
        val spec = launched.single()
        assertEquals("main.js", spec.entry)
        val context = platform.runs.resolve(spec.credential)!!
        assertEquals("price-watch", context.programId)
        val first = platform.runs.attach(context.runId!!)!!.first() as RunMessage.Event
        assertEquals("onSchedule", first.event.handler)
        assertEquals(1L, program().subscriptions.single().triggerCount)
    }

    @Test fun `pausing, revoking or late timers stop delivery`() = runBlocking {
        install(); ready()
        platform.router.fireSchedule("price-watch/hourly", now)
        platform.setGrant("price-watch", "net.http", false, null, null)
        // The run is stopping at once (its credential is void); the process ends after the grace period.
        assertNull(platform.runs.resolve(launched.single().credential))
        assertTrue(schedules.synced.isEmpty())
        platform.setGrant("price-watch", "net.http", true, null, null)
        platform.setSubscriptionPaused("price-watch/hourly", true)
        assertEquals(FireResult.INACTIVE, platform.router.fireSchedule("price-watch/hourly", null))
        platform.setSubscriptionPaused("price-watch/hourly", false)
        assertEquals(FireResult.SKIPPED_LATE, platform.router.fireSchedule("price-watch/hourly", now - 3_600_000))
        assertEquals(1, launched.size)
    }

    @Test fun `ui messages need background logic and a working program`() = runBlocking {
        install(); ready()
        assertTrue(platform.router.uiMessage("price-watch", JsonPrimitive("refresh")))
        platform.setEnabled("price-watch", false)
        assertFalse(platform.router.uiMessage("price-watch", JsonPrimitive("refresh")))
    }

    @Test fun `schedule filters parse strictly and compute the next time`() {
        assertEquals(listOf("every must be an ISO duration from PT15M to P30D"), ScheduleSpec.parse("s", json("""{"every":"PT5M"}""")).second)
        assertTrue(ScheduleSpec.parse("s", json("""{"every":"PT1H","daily":"09:00"}""")).second.isNotEmpty())
        assertTrue(ScheduleSpec.parse("s", json("""{"daily":"9am"}""")).second.isNotEmpty())
        assertTrue(ScheduleSpec.parse("s", json("""{"daily":"09:00","timezone":"Mars/Base"}""")).second.isNotEmpty())
        val zone = ZoneId.of("Asia/Shanghai")
        val daily = ScheduleSpec.parse("s", json("""{"daily":"09:00","timezone":"Asia/Shanghai"}""")).first!!
        val evening = ZonedDateTime.of(2026, 10, 24, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone).toInstant().toEpochMilli(), daily.nextAfter(evening))
        val at = ScheduleSpec.parse("s", json("""{"at":"2026-10-24T09:00:00Z"}""")).first!!
        assertNull(at.nextAfter(at.atMillis!! + 1))
        assertEquals(LocalTime.of(9, 0), daily.daily)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
