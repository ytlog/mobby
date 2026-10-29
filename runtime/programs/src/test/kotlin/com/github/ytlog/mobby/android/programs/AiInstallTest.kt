package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.programs.base.ai.*
import com.github.ytlog.mobby.android.programs.base.secrets.SecretCipher
import com.github.ytlog.mobby.android.programs.base.secrets.SecretStore
import com.github.ytlog.mobby.android.programs.capability.CapabilityRegistry
import com.github.ytlog.mobby.android.programs.event.ScheduleBackend
import com.github.ytlog.mobby.android.programs.event.ScheduleSpec
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.run.ProcessSpec
import com.github.ytlog.mobby.android.programs.run.ProgramProcess
import com.github.ytlog.mobby.android.programs.run.ProgramProcessLauncher
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.*
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
class AiInstallTest {
    private val root = Files.createTempDirectory("ai-install").toFile()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val database = Room.inMemoryDatabaseBuilder(context, ProgramsDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val gateways = object : ModelGatewayPort {
        override fun gateways() = listOf(ModelGateway("g1", "api.example.com", listOf("m1", "m2")))
        override fun preferred() = "g1" to "m1"
        override suspend fun complete(gatewayId: String, model: String, request: AiRequest) = error("not called")
    }
    private val settings = AiSettings(context)
    private val platform = ProgramPlatform(context, database, ProgramLayout(File(root, "programs")), File(root, "workspace/programs"),
        { object : ProgramProcessLauncher { override fun launch(spec: ProcessSpec): ProgramProcess = error("no runs") } },
        CapabilityRegistry(listOf(FakeHandler("ai.call"), FakeHandler("storage.sql"), FakeSchedule())),
        SecretStore(database.dao(), object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain to byteArrayOf(0)
            override fun decrypt(ciphertext: ByteArray, iv: ByteArray) = ciphertext
        }) { 1L },
        object : ScheduleBackend { override fun sync(schedules: List<ScheduleSpec>) {} }, null, scope, settings, gateways) { 1_000L }
    private val source = PackageBuilder(File(root, "workspace/programs/summary")).apply {
        id = "summary"; ui = null; config = "[]"
        permissions = """[{"capability":"ai.call","required":true},{"capability":"time.schedule","required":true}]"""
    }

    @After fun close() { scope.cancel(); database.close(); root.deleteRecursively() }

    private fun install(ai: AiBinding?) = runBlocking {
        val preview = (platform.pendingInstalls().single() as PendingInstall.Ready).preview
        platform.install(source.directory(), InstallChoice(preview.pkg.checksum, setOf("ai.call", "time.schedule"), enable = true, ai = ai))
    }

    @Test fun `granting ai call needs a configured model, which is kept until the program is deleted`() = runBlocking {
        platform.start()
        source.write()
        assertThrows(IllegalArgumentException::class.java) { install(null) }
        assertThrows(IllegalArgumentException::class.java) { install(AiBinding("g1", "not-offered")) }
        assertNull(settings.binding("summary"))
        install(AiBinding("g1", "m2"))
        assertEquals(AiBinding("g1", "m2"), platform.aiBinding("summary"))
        platform.setAiBinding("summary", AiBinding("g1", "m1", callsPerDay = 5))
        assertEquals(5, platform.aiBinding("summary")!!.callsPerDay)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { platform.setAiBinding("summary", AiBinding("gone", "m1")) } }
        platform.delete("summary", withData = true)
        assertNull(platform.aiBinding("summary"))
    }
}
