package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
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
        { _, _ -> "" }, { _, _ -> "" }, { mapOf("sms" to setOf("list"), "programs" to setOf("catalog")) }, attached)
    private fun run(actions: DeviceActions, action: String, args: Map<String, String> = emptyMap()) =
        kotlinx.coroutines.runBlocking { actions.perform("programs", action, args, DeviceExecution("op") { _, _, _ -> }) }

    @Test fun `the plugin is available only while the platform port is installed`() {
        assertFalse(DeviceHost.granted(RuntimeEnvironment.getApplication(), "plugin:device:programs"))
        ProgramDevelopment.port = port
        assertTrue(DeviceHost.granted(RuntimeEnvironment.getApplication(), "plugin:device:programs"))
        assertFalse(DeviceActionDefinition("programs", "dry-run").writes)
    }

    @Test fun `malformed arguments are refused with a message naming the argument and the expected form`() {
        fun refusal(plugin: String, action: String, args: String): String {
            val failure = runCatching { DeviceActionDefinition(plugin, action).validate(Json.parseToJsonElement(args).jsonObject) }
                .exceptionOrNull() as DeviceFailure
            assertEquals(DeviceErrorCode.INVALID_ARGUMENT, failure.error.code)
            return failure.error.message
        }
        // On the tablet the AI passed events as an array and got an empty message, then guessed at quoting for several turns.
        val events = refusal("programs", "dry-run", """{"path":"p","events":[{"handler":"onSchedule"}]}""")
        assertTrue(events, "events" in events && "JSON text" in events)
        val unknown = refusal("programs", "dry-run", """{"path":"p","event":"[]"}""")
        assertTrue(unknown, "event" in unknown && "path, events, timeoutMs" in unknown)
        assertTrue(refusal("programs", "dry-run", """{"events":"[]"}""").contains("path"))
        assertTrue(refusal("programs", "validate", """{"path":"${"a".repeat(4001)}"}""").contains("path is longer than 4000"))
        assertTrue(refusal("media", "copy", """{"kind":"photo","id":"1"}""").contains("kind must be image, video or audio"))
        assertTrue(refusal("contacts", "delete", """{"id":"-3"}""").contains("id must be"))
        assertTrue(refusal("calendar", "create", """{"title":"t","start":"5","end":"1"}""").contains("start and end"))
    }

    @Test fun `commands reach the port and records need an attached program`() {
        ProgramDevelopment.port = port
        val actions = actions(setOf("price-watch"))
        assertEquals("catalog", run(actions, "catalog").data["text"]!!.jsonPrimitive.content)
        assertEquals("program_report", run(actions, "validate", mapOf("path" to "price-watch")).kind)
        assertEquals("program_report", run(actions, "dry-run", mapOf("path" to "price-watch", "timeoutMs" to "999999")).kind)
        run(actions, "records", mapOf("programId" to "price-watch"))
        val denied = runCatching { run(actions, "records", mapOf("programId" to "other")) }.exceptionOrNull() as DeviceFailure
        assertEquals(DeviceErrorCode.UNAUTHORIZED, denied.error.code)
        // On the tablet the AI read the refusal as "not installed"; it must say what is missing and how the user attaches it.
        val message = denied.error.message
        assertTrue(message, "other" in message && "says nothing about whether it is installed" in message && AppStrings.modifyWithAi in message)
        assertEquals(listOf("catalog", "validate price-watch", "dry-run price-watch 60000 [programs, sms]", "records price-watch 50"), seen)
    }
}
