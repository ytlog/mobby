package com.github.ytlog.mobby.android

import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
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
        val originalSelection = DeviceHost.conversationSelection
        val originalAuthorize = DeviceHost.authorizeSelection
        val screenRef = "plugin:device:screen"
        val selectedTurn = UUID.randomUUID().toString().takeIf { mode == ScreenMode.ADB }
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
            if (selectedTurn != null) {
                ScreenControl.setAdbPort(context, InstrumentationRegistry.getArguments().getString("adbPort").orEmpty())
                assertTrue("The device must already be paired", ScreenControl.state.value.adbPaired)
                assertFalse("Start with a selected screen capability and a disconnected backend", ScreenControl.state.value.connected)
                DeviceHost.conversationSelection = { turn -> if (turn == selectedTurn) setOf(screenRef) else originalSelection?.invoke(turn) }
                DeviceHost.authorizeSelection = { turn, ref ->
                    check(turn != selectedTurn) { "The already selected screen capability must not be authorized again" }
                    originalAuthorize?.invoke(turn, ref)
                }
            } else {
                waitFor("existing accessibility service") { ScreenAccessService.connected() }
                assertTrue("Screen permission and task notifications must already be enabled", DeviceHost.granted(context, screenRef))
            }
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
                "/system/bin/false", setOf(screenRef), journal, ::register, ::register, { error("No resource export requested") },
                conversationTurn = selectedTurn).use { session ->
                val script = File(session.skills.single().parentFile, "scripts/device.cjs").readText()
                val port = Regex("const PORT=(\\d+);").find(script)!!.groupValues[1].toInt()
                val token = Regex("const TOKEN=\"([^\"]+)\";").find(script)!!.groupValues[1]
                fun request(action: String, args: Map<String, String> = emptyMap(), plugin: String = "screen"): JSONObject {
                    val body = JSONObject().put("token", token).put("protocolVersion", 1).put("requestId", UUID.randomUUID().toString())
                        .put("plugin", plugin).put("action", action).put("args", JSONObject(args))
                    return Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 30_000
                        socket.getOutputStream().write((body.toString() + "\n").toByteArray())
                        socket.getOutputStream().flush()
                        JSONObject(socket.getInputStream().bufferedReader().readLine())
                    }
                }
                fun status(receipt: JSONObject) = request("status", mapOf("requestId" to receipt.getString("requestId")), "operation")
                fun waitForSuccess(receipt: JSONObject): JSONObject {
                    var latest = receipt
                    waitFor("The original screen request must finish") {
                        latest = status(receipt)
                        latest.optString("status") in setOf("succeeded", "failed", "cancelled", "unconfirmed")
                    }
                    assertEquals(latest.toString(), "succeeded", latest.optString("status"))
                    return latest
                }
                fun perform(action: String, args: Map<String, String> = emptyMap()): JSONObject {
                    val response = request(action, args)
                    assertEquals("$action: " + response.optJSONObject("error")?.optString("code"), "succeeded", response.optString("status"))
                    return response.getJSONObject("result").getJSONObject("data")
                }
                val apps = perform("apps")
                if (selectedTurn != null) {
                    assertTrue("The first action must restore the saved ADB connection", ScreenControl.state.value.connected)
                    assertFalse("The selected screen must not prompt for conversation authorization", journal.sawAuthorizationPrompt)
                }
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
                    // A tooltip popup without fields must not hide the page's fields from the private form.
                    assertTrue(inputProbe(ScreenControlFixtureActivity.SHOW_TIP))
                    val fixture = "mobby-probe-only-secret"
                    val response = request("request_input")
                    assertEquals("awaiting_user", response.optString("status"))
                    val operation = checkNotNull(journal.privateInput())
                    val promptId = requireNotNull(operation.requiresAttention).promptId
                    assertEquals(listOf("Probe input", "Probe code"),
                        com.github.ytlog.mobby.android.device.PrivateScreenInput.labels(operation.operationId, promptId))
                    assertTrue(com.github.ytlog.mobby.android.device.PrivateScreenInput.submit(
                        operation.operationId, promptId, listOf(fixture, "probe-code-fixture")))
                    val result = waitForSuccess(response)
                    assertEquals("succeeded", result.optString("status"))
                    assertFalse(result.toString().contains(fixture))
                    assertTrue(inputProbe(ScreenControlFixtureActivity.HIDE_TIP))
                    val afterPrivateInput = perform("snapshot")
                    assertFalse(afterPrivateInput.toString().contains(fixture))
                    assertFalse(afterPrivateInput.toString().contains("probe-code-fixture"))
                    assertEquals("private_input", afterPrivateInput.optString("screenshotStatus"))

                    val handoff = request("request_handoff")
                    assertEquals("awaiting_user", handoff.optString("status"))
                    val handoffPrompt = checkNotNull(journal.attention("screen.handoff"))
                    assertTrue(com.github.ytlog.mobby.android.device.PrivateScreenInput.chooseTakeOver(
                        handoffPrompt.operationId, requireNotNull(handoffPrompt.requiresAttention).promptId))
                    waitFor("User must own the same display") { ScreenControl.state.value.manualControlId != null }
                    val owner = checkNotNull(ScreenControl.state.value.manualControlId)
                    assertEquals(display, ScreenControl.state.value.displayId)
                    val manualCode = ScreenControlFixtureActivity.MANUAL_TEXT
                    val field = Regex("Input center: (\\d+),(\\d+)").find(afterPrivateInput.getString("text"))!!
                    val fieldX = field.groupValues[1].toFloat() / geometry.groupValues[3].toFloat()
                    val fieldY = field.groupValues[2].toFloat() / geometry.groupValues[4].toFloat()
                    fun touch(phase: ScreenTouchPhase, x: Float, y: Float) =
                        ScreenControl.manualInput(context, owner, ManualScreenInput.Touch(phase, x, y))
                    val focusButton = Regex("Focus button: (\\d+),(\\d+)").find(afterPrivateInput.getString("text"))!!
                    val focusX = focusButton.groupValues[1].toFloat() / geometry.groupValues[3].toFloat()
                    val focusY = focusButton.groupValues[2].toFloat() / geometry.groupValues[4].toFloat()
                    // Apps such as Douyin show a tooltip popup over the page; the field stays in the page's window below it.
                    assertTrue(inputProbe(ScreenControlFixtureActivity.SHOW_TIP))
                    touch(ScreenTouchPhase.DOWN, focusX, focusY)
                    touch(ScreenTouchPhase.UP, focusX, focusY)
                    assertTrue("The page must focus its field without a tap on the field",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_FOCUS))
                    assertEquals("Typing must open for a field focused by the page itself",
                        "Probe input", ScreenControl.prepareManualText(context, owner))
                    assertTrue(inputProbe(ScreenControlFixtureActivity.CLEAR_INPUT_FOCUS))
                    ScreenControl.manualInput(context, owner, ManualScreenInput.Text(manualCode))
                    assertTrue("Changing focus after opening the editor must keep the original destination",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_INPUT))
                    touch(ScreenTouchPhase.DOWN, fieldX, fieldY)
                    touch(ScreenTouchPhase.UP, fieldX, fieldY)
                    assertTrue("The fixture must relinquish focus before local text is submitted",
                        inputProbe(ScreenControlFixtureActivity.CLEAR_INPUT_FOCUS))
                    assertEquals("Probe input", ScreenControl.prepareManualText(context, owner))
                    ScreenControl.manualInput(context, owner, ManualScreenInput.Text(manualCode))
                    assertTrue("Manual text must reach the originally selected field",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_INPUT))
                    assertTrue(inputProbe(ScreenControlFixtureActivity.HIDE_TIP))
                    touch(ScreenTouchPhase.DOWN, x, y)
                    touch(ScreenTouchPhase.UP, x, y)
                    val track = Regex("Slider track: (\\d+),(\\d+),(\\d+)").find(afterPrivateInput.getString("text"))!!
                    val (trackX, trackY, trackWidth) = track.groupValues.drop(1).map(String::toFloat)
                    val sliderY = trackY / geometry.groupValues[4].toFloat()
                    fun sliderX(progress: Int) = (trackX + trackWidth * progress / 100) / geometry.groupValues[3].toFloat()
                    ScreenControl.refreshPreview()
                    val beforeSliderFrame = checkNotNull(ScreenControl.state.value.jpeg)
                    touch(ScreenTouchPhase.DOWN, sliderX(20), sliderY)
                    touch(ScreenTouchPhase.MOVE, sliderX(65), sliderY)
                    assertTrue("Slider must follow the finger before release",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_SLIDER, "progress", 65))
                    waitFor("A new preview must arrive while the slider is still held") {
                        runBlocking { ScreenControl.refreshPreview() }
                        ScreenControl.state.value.jpeg?.let { !it.contentEquals(beforeSliderFrame) } == true
                    }
                    touch(ScreenTouchPhase.UP, sliderX(72), sliderY)
                    assertTrue("Slider must land on the release coordinate",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_SLIDER, "progress", 72))
                    val previousY = scrollPosition(afterPrivateInput.getString("text"))
                    touch(ScreenTouchPhase.DOWN, 0.5f, 0.85f)
                    touch(ScreenTouchPhase.MOVE, 0.5f, 0.70f)
                    touch(ScreenTouchPhase.MOVE, 0.5f, 0.55f)
                    assertTrue("The virtual screen must scroll while the finger is still down",
                        inputProbe(ScreenControlFixtureActivity.CHECK_MANUAL_SCROLL, "previousY", previousY))
                    touch(ScreenTouchPhase.UP, 0.5f, 0.40f)
                    // During the user's takeover a field opens the phone keyboard on the main screen; the virtual display hosts none.
                    assertTrue(inputProbe(ScreenControlFixtureActivity.ALLOW_KEYBOARD))
                    touch(ScreenTouchPhase.DOWN, fieldX, fieldY)
                    touch(ScreenTouchPhase.UP, fieldX, fieldY)
                    waitFor("The takeover keyboard must show on the main screen") {
                        val ime = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method").use { output ->
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().readText()
                        }
                        "mInputShown=true" in ime && "mCurTokenDisplayId=0" in ime
                    }
                    assertFalse("The virtual display must not host the keyboard", inputProbe(ScreenControlFixtureActivity.CHECK_KEYBOARD))
                    instrumentation.sendStatus(0, Bundle().apply { putString("screen_probe_phase", "main_keyboard_for_takeover") })
                    assertEquals("Typing preserves manual control", owner, ScreenControl.state.value.manualControlId)
                    verifyMainKeyboardAfterTakeover(returned)
                    assertEquals("awaiting_user", status(handoff).optString("status"))
                    ScreenControl.handBack(context, owner)
                    val handoffResult = waitForSuccess(handoff)
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
                    // A takeover from the preview stops the agent at once; request_handoff then waits without a new prompt.
                    val userOwner = ScreenControl.takeOver(context)
                    val refusedAt = SystemClock.elapsedRealtime()
                    val stopped = request("click", mapOf("query" to "Increment"))
                    assertEquals("USER_IN_CONTROL", stopped.optJSONObject("error")?.optString("code"))
                    assertTrue("A refused action must not wait for the user", SystemClock.elapsedRealtime() - refusedAt < 5_000)
                    val waiting = request("request_handoff")
                    assertEquals("awaiting_user", waiting.optString("status"))
                    assertEquals("awaiting_user", status(waiting).optString("status"))
                    ScreenControl.handBack(context, userOwner)
                    waitForSuccess(waiting)
                    assertTrue("The refused click must not have run", perform("snapshot").getString("text").contains("Count: 3"))
                }
                perform("back")
                session.awaitIdle { }
            }
            waitFor("The bridge must release its display after the session closes") {
                val screen = ScreenControl.state.value
                !screen.active && screen.displayId == null
            }
        } finally {
            DeviceHost.conversationSelection = originalSelection
            DeviceHost.authorizeSelection = originalAuthorize
            if (!ScreenControl.state.value.active) {
                ScreenControl.disconnect(); ScreenControl.select(context, previous)
                if (wasConnected && previous != ScreenMode.CURRENT) ScreenControl.connect(context)
            }
            root.deleteRecursively()
            foregroundMobby()
        }
    }

    private fun verifyMainKeyboardAfterTakeover(main: MainActivity) {
        lateinit var editor: EditText
        lateinit var dialog: AlertDialog
        instrumentation.runOnMainSync {
            editor = EditText(main)
            dialog = AlertDialog.Builder(main).setView(editor).create()
            dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.show()
            editor.requestFocus()
        }
        try {
            val center = IntArray(2)
            waitFor("The main-display input dialog must lay out its editor") {
                var laidOut = false
                instrumentation.runOnMainSync {
                    laidOut = editor.isShown && editor.width > 0 && editor.height > 0
                    if (laidOut) {
                        editor.getLocationOnScreen(center)
                        center[0] += editor.width / 2
                        center[1] += editor.height / 2
                    }
                }
                laidOut
            }
            // Match the user's tap on the main screen rather than only changing view focus.
            val downAt = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                    center[0].toFloat(), center[1].toFloat(), 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.sendPointerSync(event)
                } finally { event.recycle() }
            }
            waitFor("An input dialog on the main display must show its keyboard after leaving takeover") {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = editor.display?.displayId == 0 &&
                        editor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    if (!visible && editor.hasWindowFocus()) editor.windowInsetsController?.show(WindowInsets.Type.ime())
                }
                visible
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("screen_probe_phase", "main_keyboard_visible") })
        } finally {
            instrumentation.runOnMainSync { dialog.dismiss() }
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

    private fun inputProbe(action: String, key: String = "", value: Int = 0): Boolean {
        val response = java.util.concurrent.CompletableFuture<Boolean>()
        val context = instrumentation.context
        val probe = Intent(action).setPackage(context.packageName)
        if (key.isNotEmpty()) probe.putExtra(key, value)
        context.sendOrderedBroadcast(probe, null, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                response.complete(resultCode == android.app.Activity.RESULT_OK)
            }
        }, null, android.app.Activity.RESULT_CANCELED, null, null)
        return response.get(5, java.util.concurrent.TimeUnit.SECONDS)
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
        @Volatile var sawAuthorizationPrompt = false
            private set
        fun privateInput() = attention("screen.private_input")
        fun attention(reason: String) = records.values.firstOrNull { it.requiresAttention?.reasonCode == reason }
        override suspend fun admit(request: DeviceAdmission): AdmittedDevice {
            records[request.requestId]?.let { return AdmittedDevice(it, false) }
            val operation = DeviceOperation("op-${request.requestId}", request.requestId, 1, request.plugin, request.action,
                request.displayType, DeviceStatus.QUEUED, "queued", request.subject, request.input)
            records[request.requestId] = operation
            return AdmittedDevice(operation, true)
        }
        override suspend fun update(operation: DeviceOperation): DeviceOperation {
            if (operation.requiresAttention?.reasonCode == "device.authorize") sawAuthorizationPrompt = true
            records[operation.requestId] = operation
            return operation
        }
        override suspend fun find(requestId: String) = records[requestId]
    }
}
