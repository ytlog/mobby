package com.github.ytlog.mobby.android.device

import org.robolectric.RuntimeEnvironment
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProgramDevelopmentTest {
    private val dir = Files.createTempDirectory("dev").toFile()
    private val seen = mutableListOf<String>()
    private val port = object : ProgramDevelopmentPort {
        override fun catalog() = "catalog".also { seen += "catalog" }
        override fun validate(path: String) = buildJsonObject { put("valid", true) }.also { seen += "validate $path" }
        override fun dryRun(path: String, events: String, timeoutMillis: Long, allowedDeviceActions: Map<String, Set<String>>) =
            buildJsonObject { put("ran", true) }.also { seen += "dry-run $path $timeoutMillis ${allowedDeviceActions.keys.sorted()}" }
        override fun records(programId: String, limit: Int) = buildJsonObject { put("runs", JsonArray(emptyList())) }.also { seen += "records $programId $limit" }
    }

    @After fun clean() { ProgramDevelopment.port = null; dir.deleteRecursively() }

    private fun actions(attached: Set<String>) = DeviceActions(RuntimeEnvironment.getApplication(), dir, dir, DeviceActionGate(),
        { _, _ -> "" }, { _, _ -> "" }, mapOf("sms" to setOf("list"), "programs" to setOf("catalog")), attached)
    private fun run(actions: DeviceActions, action: String, args: Map<String, String> = emptyMap()) =
        actions.perform("programs", action, args, DeviceExecution("op") { _, _, _ -> })

    @Test fun `the plugin is available only while the platform port is installed`() {
        assertFalse(DeviceHost.granted(RuntimeEnvironment.getApplication(), "plugin:device:programs"))
        ProgramDevelopment.port = port
        assertTrue(DeviceHost.granted(RuntimeEnvironment.getApplication(), "plugin:device:programs"))
        assertFalse(DeviceActionDefinition("programs", "dry-run").writes)
    }

    @Test fun `commands reach the port and records need an attached program`() {
        ProgramDevelopment.port = port
        val actions = actions(setOf("price-watch"))
        assertEquals("catalog", run(actions, "catalog").data["text"]!!.jsonPrimitive.content)
        run(actions, "validate", mapOf("path" to "price-watch"))
        run(actions, "dry-run", mapOf("path" to "price-watch", "timeoutMs" to "999999"))
        run(actions, "records", mapOf("programId" to "price-watch"))
        val denied = runCatching { run(actions, "records", mapOf("programId" to "other")) }.exceptionOrNull() as DeviceFailure
        assertEquals(DeviceErrorCode.UNAUTHORIZED, denied.error.code)
        assertEquals(listOf("catalog", "validate price-watch", "dry-run price-watch 60000 [programs, sms]", "records price-watch 50"), seen)
    }
}
