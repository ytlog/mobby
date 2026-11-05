package com.github.ytlog.mobby.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
            DeviceHost.start(context, root, File(root, "inbox").apply { mkdirs() }, work,
                "/system/bin/false", setOf("plugin:device:screen"), TestJournal(), ::register, ::register, { error("No resource export requested") }).use { session ->
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
                perform("launch", mapOf("package" to "com.android.settings"))
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
                    instrumentation.sendStatus(0, Bundle().apply { putString("screen_probe_phase", "virtual_preview") })
                    SystemClock.sleep(3000)
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
        private val records = mutableMapOf<String, DeviceOperation>()
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
