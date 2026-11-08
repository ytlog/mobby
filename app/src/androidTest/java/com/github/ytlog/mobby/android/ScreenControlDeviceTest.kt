package com.github.ytlog.mobby.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.github.ytlog.mobby.android.conversation.ui.ScreenControlSettingsActivity
import com.github.ytlog.mobby.android.device.DeviceHost
import com.github.ytlog.mobby.android.device.ScreenAccessService
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket
import java.util.UUID

/** Real Android screen actions through the same device bridge as Agents; no gateway/model traffic. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
@android.annotation.TargetApi(33)
class ScreenControlDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun currentScreenRetainsClickCoordinatesTextAndSwipe() = exercise(ScreenMode.CURRENT)

    @Test fun builtinAdbVirtualScreenKeepsMobbyVisibleAndReleasesDisplay() {
        assumeTrue("Requires explicit built-in ADB device opt-in", InstrumentationRegistry.getArguments().getString("adb") == "true")
        exercise(ScreenMode.ADB)
    }

    private fun exercise(mode: ScreenMode) = runBlocking {
        assertTrue("Keep phone awake for screen acceptance", target.getSystemService(android.os.PowerManager::class.java).isInteractive)
        assertFalse("Unlock phone before screen acceptance", target.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val previous = ScreenControl.state.value.mode
        val wasConnected = ScreenControl.state.value.connected
        check(!ScreenControl.state.value.active) { "A screen task is already active" }
        val root = File(target.cacheDir, "screen-acceptance-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        }
        val main = foregroundMobby()
        try {
            ScreenControl.select(context, mode)
            if (mode != ScreenMode.CURRENT) {
                if (mode == ScreenMode.ADB) ScreenControl.setAdbPort(context, InstrumentationRegistry.getArguments().getString("adbPort").orEmpty())
                ScreenControl.connect(context)
            }
            else waitFor("existing accessibility service") { ScreenAccessService.connected() }
            assertTrue("Screen permission and task notifications must already be enabled", DeviceHost.granted(context, "plugin:device:screen"))
            fun register(file: File, type: String): String {
                assertEquals("image/jpeg", type)
                val image = BitmapFactory.decodeFile(file.absolutePath)
                assertNotNull("Actual screenshot must decode", image)
                image.recycle()
                return "image:${UUID.randomUUID()}"
            }
            val work = File(root, "workspace").apply { mkdirs() }
            val journal = TestJournal()
            DeviceHost.start(context, root, File(root, "inbox").apply { mkdirs() }, work,
                "/system/bin/false", setOf("plugin:device:screen"), journal, ::register, ::register, { error("No resource export requested") }).use { session ->
                val script = File(session.skills.single().parentFile, "scripts/device.cjs").readText()
                val port = Regex("const PORT=(\\d+);").find(script)!!.groupValues[1].toInt()
                val token = Regex("const TOKEN=\"([^\"]+)\";").find(script)!!.groupValues[1]
                fun request(action: String, args: Map<String, String> = emptyMap()): JSONObject {
                    val body = JSONObject().put("token", token).put("protocolVersion", 1).put("requestId", UUID.randomUUID().toString())
                        .put("plugin", "screen").put("action", action).put("args", JSONObject(args))
                    return Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 30_000
                        socket.getOutputStream().write((body.toString() + "\n").toByteArray())
                        socket.getOutputStream().flush()
                        JSONObject(socket.getInputStream().bufferedReader().readLine())
                    }
                }
                fun perform(action: String, args: Map<String, String> = emptyMap()): JSONObject {
                    val response = request(action, args)
                    assertEquals("$action: " + response.optJSONObject("error")?.optString("code"), "succeeded", response.optString("status"))
                    return response.getJSONObject("result").getJSONObject("data")
                }
                val apps = perform("apps")
                assertEquals(mode.name.lowercase(), apps.getString("backend"))
                val display = apps.getInt("displayId")
                assertEquals(mode == ScreenMode.CURRENT, display == 0)
                val fixturePackage = instrumentation.context.packageName
                // Use the actual Agent launch path, including its selected-display contract.
                perform("launch", mapOf("package" to fixturePackage))
                SystemClock.sleep(1000)
                var observed = perform("snapshot")
                assertEquals(fixturePackage, observed.getString("packageName"))
                assertEquals("captured", observed.getString("screenshotStatus"))
                perform("click", mapOf("query" to "Increment"))
                observed = perform("snapshot")
                assertTrue(observed.getString("text").contains("Count: 1"))
                val geometry = Regex("Button center: (\\d+),(\\d+)/(\\d+),(\\d+)").find(observed.getString("text"))!!
                val x = geometry.groupValues[1].toFloat() / geometry.groupValues[3].toFloat()
                val y = geometry.groupValues[2].toFloat() / geometry.groupValues[4].toFloat()
                perform("tap", mapOf("x" to x.toString(), "y" to y.toString()))
                assertTrue(perform("snapshot").getString("text").contains("Count: 2"))
                // Like current-screen mode, a sole editable field need not already have focus.
                perform("type", mapOf("text" to "虚拟屏中文输入"))
                val before = perform("snapshot").getString("text")
                assertTrue(before.contains("虚拟屏中文输入"))
                fun scrollPosition(text: String) = Regex("Scroll position: (\\d+)").find(text)!!.groupValues[1].toInt()
                assertEquals(0, scrollPosition(before))
                perform("swipe", mapOf("x" to "0.5", "y" to "0.85", "toX" to "0.5", "toY" to "0.4"))
                assertTrue("Swipe must move the actual scroll container", scrollPosition(perform("snapshot").getString("text")) > 0)
                perform("click", mapOf("query" to "Open detail"))
                assertTrue(perform("snapshot").getString("text").contains("Probe detail"))
                perform("back")
                assertTrue("Back must return within the selected display", perform("snapshot").getString("text").contains("Count: 2"))
                if (mode != ScreenMode.CURRENT) {
                    perform("click", mapOf("query" to "Secure field"))
                    assertNotEquals("Password inputs must remain refused", "succeeded",
                        request("type", mapOf("text" to "probe-only-dummy")).optString("status"))
                    assertTrue(ScreenControl.state.value.active)
                    assertNotNull(ScreenControl.state.value.jpeg)
                    instrumentation.runOnMainSync {
                        assertEquals(0, main.display!!.displayId)
                        assertTrue(main.window.decorView.isShown)
                        assertTrue("App preview must keep the main display awake", hasKeepScreen(main.window.decorView))
                    }
                    perform("home")
                    assertTrue(perform("snapshot").getString("text").contains("Virtual apps"))
                    perform("recents")
                    observed = perform("snapshot")
                    assertTrue(observed.getString("text").contains("Virtual tasks"))
                    assertTrue(observed.getString("text").contains(fixturePackage))
                    perform("click", mapOf("query" to fixturePackage))
                    observed = perform("snapshot")
                    assertEquals(fixturePackage, observed.getString("packageName"))
                    assertTrue("Switching tasks must preserve App state", observed.getString("text").contains("Count: 2"))
                    instrumentation.runOnMainSync {
                        assertTrue("mobby must stay resumed on the main screen",
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(main))
                    }
                    ActivityScenario.launch(ScreenControlSettingsActivity::class.java).use { settings ->
                        waitFor("The preview must also be visible on the screen settings page") {
                            var visible = false
                            settings.onActivity { visible = hasKeepScreen(it.window.decorView) }
                            visible
                        }
                    }
                    val returned = foregroundMobby()
                    SystemClock.sleep(1000)
                    instrumentation.sendStatus(0, Bundle().apply { putString("screen_probe_phase", "virtual_preview") })
                    SystemClock.sleep(3000)
                    verifyBackgroundPreview(returned)
                    perform("click", mapOf("query" to "Secure field"))
                    val fixture = "mobby-probe-only-secret"
                    val response = java.util.concurrent.CompletableFuture.supplyAsync {
                        request("request_input")
                    }
                    waitFor("Private entry prompt must appear") { journal.privateInput() != null || response.isDone }
                    val operation = checkNotNull(journal.privateInput()) {
                        "No prompt: " + response.get().optJSONObject("error")?.optString("code")
                    }
                    val promptId = requireNotNull(operation.requiresAttention).promptId
                    assertEquals(listOf("Probe input", "Probe code"),
                        com.github.ytlog.mobby.android.device.PrivateScreenInput.labels(operation.operationId, promptId))
                    assertTrue(com.github.ytlog.mobby.android.device.PrivateScreenInput.submit(
                        operation.operationId, promptId, listOf(fixture, "probe-code-fixture")))
                    val result = response.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals("succeeded", result.optString("status"))
                    assertFalse(result.toString().contains(fixture))
                    val afterPrivateInput = perform("snapshot")
                    assertFalse(afterPrivateInput.toString().contains(fixture))
                    assertFalse(afterPrivateInput.toString().contains("probe-code-fixture"))
                    assertEquals("private_input", afterPrivateInput.optString("screenshotStatus"))

                    val handoff = java.util.concurrent.CompletableFuture.supplyAsync { request("request_handoff") }
                    waitFor("Manual control prompt must appear") { journal.attention("screen.handoff") != null || handoff.isDone }
                    val handoffPrompt = checkNotNull(journal.attention("screen.handoff"))
                    assertTrue(com.github.ytlog.mobby.android.device.PrivateScreenInput.chooseTakeOver(
                        handoffPrompt.operationId, requireNotNull(handoffPrompt.requiresAttention).promptId))
                    waitFor("User must own the same display") { ScreenControl.state.value.manualControlId != null }
                    val owner = checkNotNull(ScreenControl.state.value.manualControlId)
                    assertEquals(display, ScreenControl.state.value.displayId)
                    val manualCode = "mobby-manual-code-fixture"
                    ScreenControl.manualInput(context, owner, ManualScreenInput.Text(manualCode))
                    ScreenControl.manualInput(context, owner, ManualScreenInput.Gesture(listOf(ScreenTouch(x, y, 0), ScreenTouch(x, y, 80))))
                    ScreenControl.manualInput(context, owner, ManualScreenInput.Gesture(listOf(
                        ScreenTouch(0.5f, 0.85f, 0), ScreenTouch(0.5f, 0.70f, 100),
                        ScreenTouch(0.5f, 0.55f, 200), ScreenTouch(0.5f, 0.40f, 300),
                    )))
                    ScreenControl.handBack(context, owner)
                    val handoffResult = handoff.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals("succeeded", handoffResult.optString("status"))
                    assertFalse(handoffResult.toString().contains(manualCode))
                    val staleTap = request("tap", mapOf("x" to x.toString(), "y" to y.toString()))
                    assertEquals("failed", staleTap.optString("status"))
                    assertEquals("UNAVAILABLE", staleTap.getJSONObject("error").optString("code"))
                    val afterHandoff = perform("snapshot")
                    assertTrue(afterHandoff.getString("text").contains("Count: 3"))
                    assertTrue("Manual drag must move the actual scroll container",
                        scrollPosition(afterHandoff.getString("text")) > scrollPosition(afterPrivateInput.getString("text")))
                    assertFalse(afterHandoff.toString().contains(manualCode))
                    assertEquals("private_input", afterHandoff.optString("screenshotStatus"))
                }
                perform("back")
                session.awaitIdle { }
            }
            assertFalse(ScreenControl.state.value.active)
            assertNull(ScreenControl.state.value.displayId)
        } finally {
            if (!ScreenControl.state.value.active) {
                ScreenControl.disconnect(); ScreenControl.select(context, previous)
                if (wasConnected && previous != ScreenMode.CURRENT) ScreenControl.connect(context)
            }
            root.deleteRecursively()
            foregroundMobby()
        }
    }

    private fun verifyBackgroundPreview(main: MainActivity) {
        val permitted = Settings.canDrawOverlays(target)
        instrumentation.runOnMainSync {
            assertTrue("Move mobby out of the foreground", main.moveTaskToBack(true))
        }
        waitFor("Background preview must follow the system overlay permission ($permitted)") {
            var matches = false
            instrumentation.runOnMainSync {
                val stopped = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(main) == Stage.STOPPED
                matches = stopped && hasSystemPreview() == permitted
            }
            matches
        }
        val returned = foregroundMobby()
        waitFor("The preview must return to mobby without a second system window") {
            var restored = false
            instrumentation.runOnMainSync { restored = hasKeepScreen(returned.window.decorView) && !hasSystemPreview() }
            restored
        }
    }

    private fun hasSystemPreview() = WindowInspector.getGlobalWindowViews().any {
        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
            hasKeepScreen(it)
    }

    private fun hasKeepScreen(view: View): Boolean = view.keepScreenOn ||
        (view is ViewGroup && (0 until view.childCount).any { hasKeepScreen(view.getChildAt(it)) })
    private fun foregroundMobby(): MainActivity {
        instrumentation.runOnMainSync {
            target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (InstrumentationRegistry.getArguments().getString("hostForeground") == "true") {
            instrumentation.sendStatus(0, Bundle().apply { putString("screen_probe_phase", "foreground_required") })
        }
        var main: MainActivity? = null
        waitFor("mobby Activity must be resumed") {
            instrumentation.runOnMainSync {
                main = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
            }
            main != null
        }
        return checkNotNull(main)
    }
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(label, predicate())
    }
    private class TestJournal : DeviceOperationPort {
        private val records = java.util.concurrent.ConcurrentHashMap<String, DeviceOperation>()
        fun privateInput() = attention("screen.private_input")
        fun attention(reason: String) = records.values.firstOrNull { it.requiresAttention?.reasonCode == reason }
        override suspend fun admit(request: DeviceAdmission): AdmittedDevice {
            records[request.requestId]?.let { return AdmittedDevice(it, false) }
            val operation = DeviceOperation("op-${request.requestId}", request.requestId, 1, request.plugin, request.action,
                request.displayType, DeviceStatus.QUEUED, "queued", request.subject, request.input)
            records[request.requestId] = operation
            return AdmittedDevice(operation, true)
        }
        override suspend fun update(operation: DeviceOperation): DeviceOperation { records[operation.requestId] = operation; return operation }
        override suspend fun find(requestId: String) = records[requestId]
    }
}
