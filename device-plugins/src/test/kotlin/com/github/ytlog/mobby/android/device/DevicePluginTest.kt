package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.deviceinteraction.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevicePluginTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MemoryDevices : DeviceOperationPort {
        val records = mutableMapOf<String, Pair<DeviceAdmission, DeviceOperation>>()
        override suspend fun admit(request: DeviceAdmission): AdmittedDevice {
            records[request.requestId]?.let {
                if (it.first.fingerprint != request.fingerprint) deviceFailure(DeviceErrorCode.REQUEST_CONFLICT)
                return AdmittedDevice(it.second, false)
            }
            val operation = DeviceOperation("op-${request.requestId}", request.requestId, 1, request.plugin, request.action,
                request.displayType, DeviceStatus.QUEUED, "queued", request.subject, request.input)
            records[request.requestId] = request to operation
            return AdmittedDevice(operation, true)
        }
        override suspend fun update(operation: DeviceOperation): DeviceOperation {
            val record = records.getValue(operation.requestId)
            if (!DeviceOperationRules.advances(record.second, operation)) return record.second
            records[operation.requestId] = record.first to operation
            return operation
        }
        override suspend fun find(requestId: String) = records[requestId]?.second
    }
    private fun request(plugin: String = "screen", action: String = "snapshot", args: String = "{}", version: String = "1", token: String = "fixture", id: String = "request") =
        """{"token":"$token","protocolVersion":$version,"requestId":"$id","plugin":"$plugin","action":"$action","args":$args}"""
    private fun handle(line: String, port: DeviceOperationPort = MemoryDevices(), allow: Map<String, Set<String>> = mapOf("screen" to setOf("snapshot")),
        performer: DevicePerformer = DevicePerformer { _, _, _, _ -> deviceText("screen") }): DeviceResponse =
        DeviceJson.decodeFromString(DeviceCommands.handle(line, "fixture", allow, port, performer = performer))

    @Test fun `unknown protocol version is rejected before any device action`() {
        val port = MemoryDevices()
        val response = handle(request(version = "2"), port, performer = DevicePerformer { _, _, _, _ -> error("must not dispatch") })
        assertFalse(response.accepted)
        assertEquals(DeviceErrorCode.INCOMPATIBLE_VERSION, response.error?.code)
        assertTrue(port.records.isEmpty())
    }
    @Test fun `invalid argument types and unknown fields do not admit actions`() {
        listOf("{\"surprise\":true}", "[]").forEach { args ->
            val port = MemoryDevices()
            assertFalse(handle(request(args = args), port).accepted)
            assertTrue(port.records.isEmpty())
        }
    }
    @Test fun `duplicate requests replay facts and conflicting content cannot execute`() {
        val port = MemoryDevices(); var calls = 0
        val performer = DevicePerformer { _, _, _, _ -> calls++; deviceText("value") }
        val first = handle(request(), port, performer = performer)
        assertEquals(first, handle(request(), port, performer = performer))
        assertEquals(1, calls)
        val conflict = handle(request(action = "home"), port, mapOf("screen" to setOf("home")), performer)
        assertEquals(DeviceErrorCode.REQUEST_CONFLICT, conflict.error?.code)
        assertEquals(1, calls)
    }
    @Test fun `submitted message without receipt is unconfirmed rather than successful`() {
        val response = handle(request("sms", "send", "{\"to\":\"12345\",\"body\":\"fixture\"}"),
            allow = mapOf("sms" to setOf("send")), performer = DevicePerformer { _, _, _, _ ->
                deviceResult("message_receipt", fields("submitted" to true, "sent" to "unknown", "delivered" to "unknown"), EffectState.SUBMITTED)
            })
        assertTrue(response.accepted)
        assertEquals(DeviceStatus.UNCONFIRMED, response.status)
    }
    @Test fun `bridge allowlist and token reject unauthorized commands`() {
        val allow = DeviceCatalog.allow(setOf("plugin:device:sms"))
        assertEquals(DeviceErrorCode.UNAUTHORIZED, handle(request("camera", "photo"), allow = allow).error?.code)
        assertEquals(DeviceErrorCode.UNAUTHORIZED, handle(request("sms", "list", token = "wrong"), allow = allow).error?.code)
        assertEquals(DeviceErrorCode.UNAUTHORIZED, handle(request("sms", "send"), allow = allow).error?.code)
    }
    @Test fun `closing bridge rejects a command already waiting on its socket`() {
        val performed = java.util.concurrent.CountDownLatch(1)
        val bridge = DeviceBridge { performed.countDown(); "unused" }
        java.net.Socket("127.0.0.1", bridge.port).use { socket ->
            socket.getOutputStream().write("{\"token\":\"fixture\",".toByteArray())
            Thread.sleep(150)
            bridge.close()
            runCatching { socket.getOutputStream().write("}\n".toByteArray()) }
            assertFalse(performed.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `resource export requires a reference owned by the original operation`() {
        val port = MemoryDevices()
        handle(request(), port)
        var exported = false
        val response = DeviceJson.decodeFromString<DeviceResponse>(DeviceCommands.handle(
            request("operation", "resource", "{\"requestId\":\"request\",\"resourceRef\":\"unowned\"}"), "fixture", emptyMap(), port,
            exportResource = { exported = true; "/tmp/unused" }, performer = DevicePerformer { _, _, _, _ -> error("query cannot execute") }))
        assertEquals(DeviceErrorCode.UNAUTHORIZED, response.error?.code)
        assertFalse(exported)
    }
    @Test fun `generated helper runs against the actual protocol bridge`() {
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.canExecute() }?.absolutePath
        org.junit.Assume.assumeTrue("Node is needed for CLI integration", node != null)
        val port = MemoryDevices(); var calls = 0
        val bridge = DeviceBridge { line -> DeviceCommands.handle(line, "fixture", mapOf("screen" to setOf("snapshot")), port,
            performer = DevicePerformer { _, _, _, _ -> calls++; deviceText("observed") }) }
        try {
            val script = temporary.newFile("device.cjs").apply { writeText(DeviceSkillPack.script(bridge.port, "fixture", "screen")) }
            repeat(2) {
                val process = ProcessBuilder(node!!, script.absolutePath, "snapshot", "--request-id", "stable-request").start()
                val response = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                assertEquals(DeviceStatus.SUCCEEDED, DeviceJson.decodeFromString<DeviceResponse>(response).status)
            }
            assertEquals(1, calls)
        } finally { bridge.close() }
    }

    @Test fun `write grant is absent until its own ref is enabled`() {
        assertTrue(DeviceCatalog.missingParent(setOf("plugin:device:sms:send")))
        assertFalse(DeviceCatalog.missingParent(setOf("plugin:device:sms", "plugin:device:sms:send")))
        assertEquals(setOf("list"), DeviceCatalog.allow(setOf("plugin:device:sms"))["sms"])
        assertEquals(setOf("list", "send"), DeviceCatalog.allow(setOf("plugin:device:sms", "plugin:device:sms:send"))["sms"])
        assertFalse(DeviceCatalog.isKnown("plugin:PHONE:ACCESSIBILITY"))
    }

    @Test fun `a screen run keeps the display awake until the session closes`() {
        var holds = 0
        val bridge = DeviceBridge { error("unused") }
        val session = openDeviceSession(setOf("plugin:device:screen", "plugin:device:sms"), {
            holds += 1
            AutoCloseable { holds -= 1 }
        }) { bridge to emptyList() }
        assertEquals(1, holds)
        session.close()
        assertEquals(0, holds)
        session.close()
        assertEquals(0, holds)
    }

    @Test fun `a run without the screen plugin does not keep the display awake`() {
        val bridge = DeviceBridge { error("unused") }
        val session = openDeviceSession(setOf("plugin:device:sms"), { error("should not hold") }) { bridge to emptyList() }
        session.close()
    }

    @Test fun `a failed screen session releases the display hold`() {
        var holds = 0
        assertThrows(IllegalStateException::class.java) {
            openDeviceSession(setOf("plugin:device:screen"), {
                holds += 1
                AutoCloseable { holds -= 1 }
            }) { error("bridge failed") }
        }
        assertEquals(0, holds)
    }

    @Test fun `screen hold is acquired once and released when the service drops it`() {
        var held = 0
        val stay = ScreenStay(object : DisplayHold {
            override fun hold() { held += 1 }
            override fun release() { held -= 1 }
        })
        val first = stay.acquire()
        val second = stay.acquire()
        assertEquals(1, held)
        first.close()
        assertEquals(1, held)
        stay.drop()
        assertEquals(0, held)
        second.close()
        assertEquals(0, held)
    }

    @Test fun `a failed screen hold can be acquired later`() {
        var fail = true
        var held = 0
        val stay = ScreenStay(object : DisplayHold {
            override fun hold() {
                if (fail) error("no service")
                held += 1
            }
            override fun release() { held -= 1 }
        })
        assertThrows(IllegalStateException::class.java) { stay.acquire() }
        assertEquals(0, held)
        fail = false
        val session = stay.acquire()
        assertEquals(1, held)
        session.close()
        assertEquals(0, held)
    }

    @Test fun `screen skill tells the model the display stays on for the run`() {
        val markdown = DeviceSkillPack.write(temporary.newFolder(), "/bin/node", 9, "tok", setOf("plugin:device:screen")).single().readText()
        assertTrue(markdown.contains("stays on"))
        assertTrue(markdown.contains("Do not change the system screen timeout"))
    }

    @Test fun `skill text includes only the actions enabled for this run`() {
        val root = temporary.newFolder()
        val skill = DeviceSkillPack.write(root, "/bin/node", 43123, """tok"en""", setOf("plugin:device:sms")).single()
        val markdown = skill.readText()
        assertTrue(markdown.contains("name: mobby-sms"))
        assertTrue(markdown.contains("list"))
        assertFalse(markdown.contains("send {"))
        val helper = File(skill.parentFile, "scripts/device.cjs").readText()
        assertTrue(helper.contains("127.0.0.1"))
        assertTrue(helper.contains("sms"))
        assertTrue(helper.contains("tok\\\"en"))
        val both = DeviceSkillPack.write(temporary.newFolder(), "/bin/node", 9, "tok", setOf("plugin:device:sms", "plugin:device:sms:send")).single().readText()
        assertTrue(both.contains("send {"))
    }

    @Test fun `office files round-trip text and stay inside the workspace`() {
        val workspace = temporary.newFolder("workspace")
        val inbox = temporary.newFolder("inbox")
        val docx = File(workspace, "notes.docx")
        OfficePackage.write(docx, "第一段\n第二段")
        assertEquals("docx 段落 2", OfficePackage.inspect(docx))
        assertEquals("第一段\n第二段", OfficePackage.read(docx))
        val xlsx = File(workspace, "sheet.xlsx")
        OfficePackage.write(xlsx, "甲\n乙")
        assertTrue(OfficePackage.read(xlsx).contains("甲"))
        val pptx = File(workspace, "deck.pptx")
        OfficePackage.write(pptx, "封面")
        assertEquals("pptx 幻灯片 1", OfficePackage.inspect(pptx))
        assertEquals("封面", OfficePackage.read(pptx))
        assertEquals(docx.canonicalFile, DevicePaths.resolve(workspace, inbox, "notes.docx"))
        assertThrows(IllegalStateException::class.java) { DevicePaths.resolve(workspace, inbox, "../secret") }
        val outside = temporary.newFile("outside.txt")
        assertThrows(IllegalStateException::class.java) { DevicePaths.resolve(workspace, inbox, outside.absolutePath) }
    }

    private fun json(line: String) = Json.parseToJsonElement(line).jsonObject
}