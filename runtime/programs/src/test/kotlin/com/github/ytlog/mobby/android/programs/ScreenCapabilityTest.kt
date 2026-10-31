package com.github.ytlog.mobby.android.programs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.device.ScreenNode
import com.github.ytlog.mobby.android.device.ScreenRefusal
import com.github.ytlog.mobby.android.device.ScreenRefused
import com.github.ytlog.mobby.android.device.ScreenTarget
import com.github.ytlog.mobby.android.device.ScreenWindow
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.CallPipeline
import com.github.ytlog.mobby.android.programs.call.CallRequest
import com.github.ytlog.mobby.android.programs.call.CallResponse
import com.github.ytlog.mobby.android.programs.call.CallStatus
import com.github.ytlog.mobby.android.programs.call.DryRunScope
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.*
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.screen.SCREEN_CAPABILITIES
import com.github.ytlog.mobby.android.programs.screen.ScreenCapability
import com.github.ytlog.mobby.android.programs.screen.ScreenPort
import com.github.ytlog.mobby.android.programs.store.GrantRow
import com.github.ytlog.mobby.android.programs.store.ProgramRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDatabase
import kotlinx.coroutines.runBlocking
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
class ScreenCapabilityTest {
    private val root = Files.createTempDirectory("screen").toFile()
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ProgramsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = database.dao()
    private val skip = ScreenNode(1, 0, "android.widget.TextView", "com.shop:id/skip", "跳过 3", null, true, false, true, listOf(850, 40, 980, 90))

    private inner class Port : ScreenPort {
        var on = true
        var window = ScreenWindow("com.shop", "SplashActivity", listOf(ScreenNode(0, null, "android.widget.FrameLayout", null, null, null, false, false, true,
            listOf(0, 0, 1000, 1000)), skip), false, 5)
        var refusal: ScreenRefused? = null
        val acted = mutableListOf<String>()
        override fun available() = on
        override fun read() = refusal?.let { throw it } ?: window
        override fun click(packageName: String, target: ScreenTarget): ScreenNode {
            refusal?.let { throw it }
            acted += "click $packageName ${target.textContains ?: target.text}"
            return skip
        }
        override fun back(packageName: String) { refusal?.let { throw it }; acted += "back $packageName" }
        override fun home(packageName: String) { refusal?.let { throw it }; acted += "home $packageName" }
    }
    private val port = Port()
    private val registry = CapabilityRegistry(SCREEN_CAPABILITIES.map { ScreenCapability(it, port) })
    private val gate = PermissionGate(registry) { 1 }
    private val pipeline = CallPipeline(registry, gate, dao, ProgramLayout(File(root, "programs")), { 1 })
    private val run = CallContext("p", "1", Surface.BACKGROUND, runId = "r")
    private var ids = 0

    private fun grant(capability: String, packages: Set<String>) = runBlocking {
        val json = Scope(packages = packages).toJson().toString()
        dao.upsertProgram(ProgramRow("p", "跳过等待", "enabled", "1", 0, 0))
        dao.upsertGrant(GrantRow("p", capability, json, json, true, true, "manifest", 0, null, 1))
        PermissionManager(dao, gate) { 1 }.reload()
    }
    private fun call(capability: String, args: String, context: CallContext = run): CallResponse = runBlocking {
        pipeline.call(context, CallRequest("r-${++ids}", capability, Json.parseToJsonElement(args).jsonObject))
    }

    @After fun close() { database.close(); root.deleteRecursively() }

    @Test fun `reading returns the window in front only for granted apps`() {
        grant("screen.read", setOf("*"))
        val window = call("screen.read", "{}").result!!.jsonObject
        assertEquals("com.shop", window["packageName"]!!.jsonPrimitive.content)
        val node = window["nodes"]!!.jsonArray[1].jsonObject
        assertEquals(listOf("跳过 3", "com.shop:id/skip", "0"), listOf(node["text"], node["viewId"], node["parent"]).map { it!!.jsonPrimitive.content })
        assertEquals(listOf(850, 40, 980, 90), node["bounds"]!!.jsonArray.map { it.jsonPrimitive.int })
        // Naming an app checks it is the one in front.
        assertEquals("window_is_com.shop", call("screen.read", """{"package":"com.bank"}""").error!!.reason)
        grant("screen.read", setOf("com.bank"))
        assertEquals("package_not_granted", call("screen.read", "{}").error!!.reason)
        assertEquals("window_is_com.shop", call("screen.read", """{"package":"com.bank"}""").error!!.reason)
        port.window = port.window.copy(packageName = "com.bank")
        assertEquals(CallStatus.SUCCEEDED, call("screen.read", """{"package":"com.bank"}""").status)
    }

    @Test fun `actions name the app in front and every refusal keeps its reason`() {
        for (capability in listOf("screen.click", "screen.back", "screen.home")) grant(capability, setOf("com.shop"))
        assertEquals(CallStatus.SUCCEEDED, call("screen.click", """{"package":"com.shop","textContains":"跳过"}""").status)
        assertEquals(CallStatus.SUCCEEDED, call("screen.back", """{"package":"com.shop"}""").status)
        assertEquals(listOf("click com.shop 跳过", "back com.shop"), port.acted)
        assertEquals("$.package is required", call("screen.click", """{"textContains":"跳过"}""").error!!.reason)
        assertEquals("click_needs_text_description_view_id_or_class_name", call("screen.click", """{"package":"com.shop"}""").error!!.reason)
        assertEquals("package_not_granted", call("screen.home", """{"package":"com.bank"}""").error!!.reason)
        val refusals = mapOf(
            ScreenRefusal.NOT_UNIQUE to ProgramErrorCode.TARGET_NOT_UNIQUE, ScreenRefusal.NOT_FOUND to ProgramErrorCode.RESOURCE_MISSING,
            ScreenRefusal.WINDOW_CHANGED to ProgramErrorCode.STALE_OBSERVATION, ScreenRefusal.BUSY to ProgramErrorCode.CONDITION_UNMET,
            ScreenRefusal.LOCKED to ProgramErrorCode.CONDITION_UNMET, ScreenRefusal.OWN_APP to ProgramErrorCode.SCOPE_DENIED)
        for ((refusal, code) in refusals) {
            port.refusal = ScreenRefused(refusal, "because_${refusal.name.lowercase()}")
            val error = call("screen.click", """{"package":"com.shop","text":"跳过"}""").error!!
            assertEquals(refusal.name, code to "because_${refusal.name.lowercase()}", error.code to error.reason)
        }
        assertEquals(2, port.acted.size)
    }

    @Test fun `without the screen service the capabilities are unavailable and dry runs never touch the screen`() {
        grant("screen.click", setOf("*"))
        val dry = run.copy(runId = "dry", dryRun = DryRunScope(emptySet(), mapOf("screen.click" to Scope(packages = setOf("*")))))
        assertEquals(CallStatus.SIMULATED, call("screen.click", """{"package":"com.shop","text":"跳过 3"}""", dry).status)
        assertTrue(port.acted.isEmpty())
        port.on = false
        assertEquals(ProgramErrorCode.PERMISSION_DENIED to "system_permission_missing", call("screen.click", """{"package":"com.shop","text":"跳过 3"}""").error!!.let { it.code to it.reason })
    }
}
