package com.github.ytlog.mobby.android.device

import android.content.Context
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assume
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DeviceWorkflowManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val allow = mapOf("screen" to setOf("snapshot", "click", "type", "back"))
    private val actors = mutableListOf<DeviceWorkflowManager>()
    @Volatile private var page = "home"
    private var input = ""
    private var matches = 1
    private var actions = 0
    private var delayedBackMillis = 0L

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("device-workflow-history.db")
        page = "home"; input = ""; matches = 1; actions = 0; delayedBackMillis = 0
    }
    @After fun tearDown() { actors.forEach { it.close() }; context.deleteDatabase("device-workflow-history.db") }

    private fun manager() = DeviceWorkflowManager(context) { query, expected ->
        ScreenEvidence("com.example.store", page, if (query == null) 0 else matches,
            if (page == "search") 1 else 0, page == "search", expected != null && input == expected)
    }.also(actors::add)

    private fun invoke(manager: DeviceWorkflowManager, action: String, args: String = "{}", token: String = "fixture"): DeviceResponse {
        val line = """{"token":"$token","protocolVersion":1,"requestId":"${java.util.UUID.randomUUID()}","plugin":"workflow","action":"$action","args":$args}"""
        return DeviceJson.decodeFromString(manager.handle(line, "fixture", allow, ::perform))
    }

    @Test fun delayedBackWaitsForActualPageTransitionBeforeSealingAndReplaying() {
        delayedBackMillis = 150
        val manager = manager()
        val first = invoke(manager, "run", """{"steps":[{"action":"click","query":"显示与亮度"},{"action":"back"}]}""")
        assertEquals(DeviceStatus.SUCCEEDED, first.status)
        val record = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.single().jsonObject
        assertTrue(record["replayable"]!!.jsonPrimitive.boolean)
        assertEquals("home", page)

        val workflowId = record["workflowId"]!!.jsonPrimitive.content
        val replay = invoke(manager(), "replay", """{"workflowId":"$workflowId"}""")
        assertEquals(DeviceStatus.SUCCEEDED, replay.status)
        assertEquals(2, replay.result!!.data["completed"]!!.jsonPrimitive.int)
        assertEquals("home", page)
    }

    private fun perform(line: String): String {
        val request = DeviceJson.decodeFromJsonElement<DeviceRequest>(JsonObject(DeviceJson.parseToJsonElement(line).jsonObject - "token"))
        actions++
        when (request.action) {
            "click" -> page = "search"
            "type" -> input = request.args.getValue("text").jsonPrimitive.content
            "back" -> if (delayedBackMillis == 0L) page = "home" else Thread {
                Thread.sleep(delayedBackMillis)
                page = "home"
            }.start()
        }
        return DeviceJson.encodeToString(DeviceResponse(requestId = request.requestId, accepted = true,
            status = DeviceStatus.SUCCEEDED, result = DeviceResult("screen_observation", EffectState.CONFIRMED, JsonObject(emptyMap()))))
    }

    @Test fun `predicted steps are checked and stored as directly replayable actions without old input`() {
        val manager = manager()
        val result = invoke(manager, "run", """{"steps":[{"action":"click","query":"搜索"},{"action":"type","text":"旧关键词"}]}""")
        assertEquals(DeviceStatus.SUCCEEDED, result.status)
        assertEquals(2, actions)
        val listing = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray
        assertEquals(1, listing.size)
        val record = listing.single().jsonObject
        assertTrue(record["replayable"]!!.jsonPrimitive.boolean)
        assertEquals("input_2", record["steps"]!!.jsonArray[1].jsonObject["slot"]!!.jsonPrimitive.content)
        assertFalse(record.toString().contains("旧关键词"))

        page = "home"; input = ""; actions = 0
        val workflowId = record.getValue("workflowId").jsonPrimitive.content
        val replay = invoke(manager(), "replay", """{"workflowId":"$workflowId","values":{"input_2":"新关键词"}}""")
        assertEquals(DeviceStatus.SUCCEEDED, replay.status)
        assertEquals(2, actions)
        assertEquals("新关键词", input)
    }

    @Test fun `ambiguous target stops before any action and cannot be replayed`() {
        val manager = manager()
        matches = 2
        val result = invoke(manager, "run", """{"steps":[{"action":"click","query":"搜索"}]}""")
        assertEquals(DeviceStatus.FAILED, result.status)
        assertEquals(0, actions)
        assertTrue(invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.isEmpty())
    }

    @Test fun `saved workflow stops when the entry page changed`() {
        val manager = manager()
        val run = invoke(manager, "run", """{"steps":[{"action":"click","query":"搜索"}]}""")
        assertEquals(DeviceStatus.SUCCEEDED, run.status)
        val id = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.single().jsonObject["workflowId"]!!.jsonPrimitive.content
        page = "other"; actions = 0
        val result = invoke(manager(), "replay", """{"workflowId":"$id"}""")
        assertEquals(DeviceStatus.FAILED, result.status)
        assertEquals(0, actions)
        assertEquals("page_or_target_mismatch", result.result!!.data["stoppedReason"]!!.jsonPrimitive.content)
    }

    @Test fun `workflow command requires the active token and screen grant`() {
        val manager = manager()
        assertEquals(DeviceErrorCode.UNAUTHORIZED, invoke(manager, "list", token = "wrong").error?.code)
        assertEquals(0, actions)
    }

    @Test fun `same multi step request ID never dispatches twice`() {
        val manager = manager()
        val id = java.util.UUID.randomUUID().toString()
        fun request(query: String) = """{"token":"fixture","protocolVersion":1,"requestId":"$id","plugin":"workflow","action":"run","args":{"steps":[{"action":"click","query":"$query"}]}}"""
        val first = DeviceJson.decodeFromString<DeviceResponse>(manager.handle(request("搜索"), "fixture", allow, ::perform))
        assertEquals(DeviceStatus.SUCCEEDED, first.status)
        page = "home"
        val repeat = DeviceJson.decodeFromString<DeviceResponse>(manager.handle(request("其他"), "fixture", allow, ::perform))
        assertEquals(first, repeat)
        assertEquals(1, actions)
    }

    @Test fun `unknown screen effect stops the chain and a retry does not resend it`() {
        val manager = manager()
        val id = java.util.UUID.randomUUID().toString()
        val line = """{"token":"fixture","protocolVersion":1,"requestId":"$id","plugin":"workflow","action":"run","args":{"steps":[{"action":"click","query":"搜索"},{"action":"type","text":"测试"}]}}"""
        var calls = 0
        val uncertain: (String) -> String = { inner ->
            calls++
            val request = DeviceJson.decodeFromJsonElement<DeviceRequest>(JsonObject(DeviceJson.parseToJsonElement(inner).jsonObject - "token"))
            DeviceJson.encodeToString(DeviceResponse(requestId = request.requestId, accepted = true,
                status = DeviceStatus.UNCONFIRMED, error = DeviceError(DeviceErrorCode.RESULT_UNCONFIRMED, effectState = EffectState.UNKNOWN)))
        }
        val first = DeviceJson.decodeFromString<DeviceResponse>(manager.handle(line, "fixture", allow, uncertain))
        assertEquals(DeviceStatus.UNCONFIRMED, first.status)
        assertEquals(1, calls)
        val repeat = DeviceJson.decodeFromString<DeviceResponse>(manager.handle(line, "fixture", allow, uncertain))
        assertEquals(first, repeat)
        assertEquals(1, calls)
    }

    @Test fun `individual successful actions need task finish before replay`() {
        val manager = manager()
        val line = """{"token":"fixture","protocolVersion":1,"requestId":"${java.util.UUID.randomUUID()}","plugin":"screen","action":"click","args":{"query":"搜索"}}"""
        manager.handle(line, "fixture", allow, ::perform)
        val before = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.single().jsonObject
        assertFalse(before["replayable"]!!.jsonPrimitive.boolean)
        val finish = invoke(manager, "finish")
        assertTrue(finish.result!!.data["recordedWorkflowReplayable"]!!.jsonPrimitive.boolean)
        val after = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.single().jsonObject
        assertTrue(after["replayable"]!!.jsonPrimitive.boolean)
    }

    @Test fun `duplicate screen request does not add a second historical step`() {
        val manager = manager()
        val line = """{"token":"fixture","protocolVersion":1,"requestId":"stable-screen-request","plugin":"screen","action":"click","args":{"query":"搜索"}}"""
        manager.handle(line, "fixture", allow, ::perform)
        manager.handle(line, "fixture", allow, ::perform)
        val record = invoke(manager, "list").result!!.data["workflows"]!!.jsonArray.single().jsonObject
        assertEquals(1, record["stepCount"]!!.jsonPrimitive.int)
    }

    @Test fun `generated Node helper can submit a checked multi step workflow`() {
        val node = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "node") }
            .firstOrNull { it.canExecute() }?.absolutePath
        Assume.assumeTrue("Node is needed for bridge integration", node != null)
        val manager = manager()
        val bridge = DeviceBridge { line -> manager.handle(line, "fixture", allow, ::perform) }
        try {
            val script = temporary.newFile("device.cjs").apply { writeText(DeviceSkillPack.script(bridge.port, "fixture", "screen")) }
            val process = ProcessBuilder(node!!, script.absolutePath, "workflow-run",
                """{"steps":[{"action":"click","query":"搜索"},{"action":"type","text":"测试"}]}""").start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertTrue(output.contains("\"verified\":true"))
            assertEquals(2, actions)
        } finally { bridge.close() }
    }
}
