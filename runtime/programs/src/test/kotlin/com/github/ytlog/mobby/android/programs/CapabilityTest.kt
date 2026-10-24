package com.github.ytlog.mobby.android.programs

import com.github.ytlog.mobby.android.device.DeviceExecutionContext
import com.github.ytlog.mobby.android.device.DeviceExecutors
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CapabilityTest {
    private class FakeDevice(var granted: Boolean = true, var perform: (DeviceExecutionContext) -> DeviceResult = {
        DeviceResult("basic", EffectState.NONE, JsonObject(emptyMap()))
    }) : DevicePort {
        val calls = mutableListOf<Map<String, String>>()
        override fun validate(plugin: String, action: String, args: JsonObject) = DeviceExecutors.validate(plugin, action, args)
        override fun systemGranted(plugin: String, action: String) = granted
        override fun perform(plugin: String, action: String, args: Map<String, String>, execution: DeviceExecutionContext): DeviceResult {
            calls += args
            return perform(execution)
        }
    }

    private val root = Files.createTempDirectory("space").toFile()
    private val space = ProgramSpace("p", File(root, "files").apply { mkdirs() }, File(root, "tmp").apply { mkdirs() })
    private fun spec(id: String) = PHASE_ONE_DEVICE_CAPABILITIES.single { it.id == id }
    private fun call(args: String) = CapabilityCall(CallContext("p", "1", Surface.BACKGROUND, runId = "r"),
        Json.parseToJsonElement(args).jsonObject, space) { false }
    private fun failure(block: suspend () -> Unit) = runCatching { runBlocking { block() } }.exceptionOrNull() as CapabilityException

    @Test fun `registry rejects duplicates and malformed ids`() {
        val device = FakeDevice()
        val sms = DeviceCapabilityHandler(spec("sms.list"), device)
        assertThrows(IllegalArgumentException::class.java) { CapabilityRegistry(listOf(sms, DeviceCapabilityHandler(spec("sms.list"), device))) }
        val registry = CapabilityRegistry(PHASE_ONE_DEVICE_CAPABILITIES.map { DeviceCapabilityHandler(it, device) })
        assertEquals(PHASE_ONE_DEVICE_CAPABILITIES.map { it.id }.sorted(), registry.descriptors.map { it.id })
        assertNull(registry["screen.click"])
    }

    @Test fun `input schemas come from the device action rules`() {
        val send = DeviceCapabilityHandler(spec("sms.send"), FakeDevice()).descriptor.inputSchema
        assertEquals(500, send["properties"]!!.jsonObject["body"]!!.jsonObject["maxLength"]!!.jsonPrimitive.int)
        assertEquals(listOf("body", "to"), send["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(send["additionalProperties"]!!.jsonPrimitive.boolean)
        val create = DeviceCapabilityHandler(spec("contacts.create"), FakeDevice()).descriptor.inputSchema
        assertEquals(listOf("name"), create["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `invalid arguments are rejected before the device is touched`() {
        val device = FakeDevice()
        val handler = DeviceCapabilityHandler(spec("sms.send"), device)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, failure { handler.invoke(call("""{"to":"123","body":"hi","extra":"x"}""")) }.code)
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT, failure { handler.invoke(call("""{"to":"123"}""")) }.code)
        assertTrue(device.calls.isEmpty())
    }

    @Test fun `device facts keep their effect state`() = runBlocking {
        val device = FakeDevice(perform = { DeviceResult("message_send", EffectState.SUBMITTED, buildJsonObject { put("sent", "pending") }) })
        val outcome = DeviceCapabilityHandler(spec("sms.send"), device).invoke(call("""{"to":"+8613800138000","body":"hi"}"""))
        assertEquals(Effect.SUBMITTED, outcome.effect)
        assertEquals("pending", outcome.result.jsonObject["data"]!!.jsonObject["sent"]!!.jsonPrimitive.content)
        assertEquals(mapOf("to" to "+8613800138000", "body" to "hi"), device.calls.single())
    }

    @Test fun `device failures map to program errors without losing the effect`() {
        val device = FakeDevice()
        val handler = DeviceCapabilityHandler(spec("sms.list"), device)
        device.perform = { deviceFailure(DeviceErrorCode.PERMISSION_REVOKED) }
        assertEquals(ProgramErrorCode.PERMISSION_DENIED, failure { handler.invoke(call("{}")) }.code)
        device.perform = { deviceFailure(DeviceErrorCode.RESULT_UNCONFIRMED, effect = EffectState.UNKNOWN) }
        failure { handler.invoke(call("{}")) }.let { assertEquals(ProgramErrorCode.EFFECT_UNKNOWN, it.code); assertEquals(Effect.UNKNOWN, it.effect) }
        device.perform = { throw CancellationException("stop") }
        assertEquals(ProgramErrorCode.CANCELLED, failure { handler.invoke(call("{}")) }.code)
        device.perform = { error("not found") }
        assertEquals(ProgramErrorCode.FAILED, failure { handler.invoke(call("{}")) }.code)
    }

    @Test fun `produced files are named inside the program space only`() = runBlocking {
        val device = FakeDevice(perform = { execution ->
            val file = File(execution.tempDir, "notes.txt").apply { writeText("x") }
            DeviceResult("file_items", EffectState.CONFIRMED, JsonObject(emptyMap()), listOf(execution.registerFile(file, "text/plain")))
        })
        val outcome = DeviceCapabilityHandler(spec("device.storage.copy"), device).invoke(call("""{"name":"notes.txt"}"""))
        assertEquals(listOf("tmp/notes.txt"), outcome.result.jsonObject["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        val outside = File(root.parentFile, "elsewhere.txt")
        assertEquals("file_outside_program_space",
            runCatching { DeviceCapabilityHandler.relative(space, outside) }.exceptionOrNull().let { (it as CapabilityException).reason })
    }

    @Test fun `availability follows the Android side`() {
        val device = FakeDevice(granted = false)
        assertEquals(Availability.Unavailable(AvailabilityReason.SYSTEM_PERMISSION_MISSING), DeviceCapabilityHandler(spec("sms.list"), device).availability())
        device.granted = true
        assertEquals(Availability.Available, DeviceCapabilityHandler(spec("sms.list"), device).availability())
    }
}
