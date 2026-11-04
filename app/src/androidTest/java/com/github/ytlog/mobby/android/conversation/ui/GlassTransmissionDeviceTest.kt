package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** GPU rendering in an isolated fixture; never edits conversations, accounts or preferences. */
@RunWith(AndroidJUnit4::class)
class GlassTransmissionDeviceTest {
    @Test(timeout = 30_000) fun regularGlassProtectsControlsAndRetainsBackdrop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backgroundMode = mutableStateOf(0)
        val bounds = AtomicReference<Rect>()
        val popupBounds = AtomicReference<Rect>()
        val popupVisible = mutableStateOf(false)
        val optionSelected = mutableStateOf(false)
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        // Launch via the test shell: some devices block instrumentation's background activity start.
        val monitor = instrumentation.addMonitor(ComponentActivity::class.java.name, null, false)
        var fixture: ComponentActivity? = null
        try {
            val component = "${instrumentation.targetContext.packageName}/${ComponentActivity::class.java.name}"
            val launch = instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(launch).use { it.readBytes() }
            val activity = monitor.waitForActivityWithTimeout(5_000) as? ComponentActivity
                ?: error("GPU fixture did not start")
            fixture = activity
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme(colorScheme = MobbyDarkScheme) {
                        val backdrop = rememberLayerBackdrop()
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                                drawRect(if (backgroundMode.value == 2) Color.White else Color.Black)
                                if (backgroundMode.value == 0) {
                                    val stripe = 6.dp.toPx()
                                    var x = 0f
                                    while (x < size.width) {
                                        drawRect(Color.White, topLeft = Offset(x, 0f),
                                            size = androidx.compose.ui.geometry.Size(stripe, size.height))
                                        x += stripe * 2
                                    }
                                } else drawRect(if (backgroundMode.value == 2) Color.Black else Color.White,
                                    topLeft = Offset(size.width / 2f, 0f))
                            }
                            CompositionLocalProvider(LocalConversationBackdrop provides backdrop) {
                                GlassSurface(Modifier.align(Alignment.Center).size(240.dp, 80.dp)
                                    .onGloballyPositioned {
                                        val r = it.boundsInWindow()
                                        bounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
                                    }) {}
                            }
                            FrostedMenu(popupVisible.value, { popupVisible.value = false }) {
                                Column(Modifier.onGloballyPositioned {
                                    val origin = it.positionOnScreen()
                                    popupBounds.set(Rect(origin.x.roundToInt(), origin.y.roundToInt(),
                                        origin.x.roundToInt() + it.size.width, origin.y.roundToInt() + it.size.height))
                                }) {
                                    MenuOption("GPU option", optionSelected.value) { optionSelected.value = true; clicks.incrementAndGet() }
                                    MenuAction("GPU disabled", enabled = false) { clicks.incrementAndGet() }
                                }
                            }
                        }
                    }
                }
            }
            fun waitForContrast(sign: Int) {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var contrast = 0f
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    val r = bounds.get()
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    if (r != null && screenshot != null) {
                        val left = Color(screenshot.getPixel(r.left + r.width() / 4, r.centerY())).luminance()
                        val right = Color(screenshot.getPixel(r.left + r.width() * 3 / 4, r.centerY())).luminance()
                        contrast = (right - left) * sign
                        val rawY = (r.top - 20 * instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
                        val rawLeft = Color(screenshot.getPixel(r.left + r.width() / 4, rawY)).luminance()
                        val rawRight = Color(screenshot.getPixel(r.left + r.width() * 3 / 4, rawY)).luminance()
                        screenshot.recycle()
                        if (contrast > 0.25f && (rawRight - rawLeft) * sign > 0.9f) return
                    } else screenshot?.recycle()
                    SystemClock.sleep(100)
                }
                fail("GPU glass must transmit and track backdrop contrast; sign=$sign contrast=$contrast")
            }
            val stripe = (6f * instrumentation.targetContext.resources.displayMetrics.density)
            val deadline = SystemClock.uptimeMillis() + 5_000
            var diffused = false
            var fineContrast = 1f
            while (SystemClock.uptimeMillis() < deadline && !diffused) {
                instrumentation.waitForIdleSync()
                val r = bounds.get()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (r != null && screenshot != null) {
                    val cycle = kotlin.math.ceil((r.left + r.width() * 0.25f) / (stripe * 2)).toInt()
                    val x = ((cycle * 2 + 0.5f) * stripe).roundToInt()
                    val light = Color(screenshot.getPixel(x, r.centerY())).luminance()
                    val dark = Color(screenshot.getPixel((x + stripe).roundToInt(), r.centerY())).luminance()
                    fineContrast = kotlin.math.abs(light - dark)
                    val rawY = (r.top - 20 * instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
                    val rawLight = Color(screenshot.getPixel(x, rawY)).luminance()
                    val rawDark = Color(screenshot.getPixel((x + stripe).roundToInt(), rawY)).luminance()
                    diffused = rawLight - rawDark > 0.6f && fineContrast < 0.04f && (light + dark) / 2f > 0.03f
                }
                screenshot?.recycle()
                if (!diffused) SystemClock.sleep(100)
            }
            assertTrue("fine background must be diffused instead of showing through controls: $fineContrast", diffused)
            instrumentation.runOnMainSync { backgroundMode.value = 1 }
            waitForContrast(1)
            instrumentation.runOnMainSync { backgroundMode.value = 2 }
            waitForContrast(-1)
            instrumentation.runOnMainSync { popupVisible.value = true }
            val menuDeadline = SystemClock.uptimeMillis() + 5_000
            while (popupBounds.get() == null && SystemClock.uptimeMillis() < menuDeadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
            }
            val menu = popupBounds.get() ?: error("glass popup did not lay out")
            fun tap(y: Float) {
                val now = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, menu.exactCenterX(), y, 0)
                    instrumentation.uiAutomation.injectInputEvent(event, true)
                    event.recycle()
                }
                instrumentation.waitForIdleSync()
            }
            tap(menu.top + menu.height() * 0.75f)
            assertEquals("disabled glass menu action must not run", 0, clicks.get())
            tap(menu.top + menu.height() * 0.25f)
            assertEquals("glass menu action should dispatch once", 1, clicks.get())
            assertTrue(optionSelected.value)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertFalse("Back must dismiss the glass popup", popupVisible.value)
        } finally {
            fixture?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)

        }
    }
}
