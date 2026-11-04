package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** GPU rendering in an isolated fixture; never edits conversations, accounts or preferences. */
@RunWith(AndroidJUnit4::class)
class GlassTransmissionDeviceTest {
    @Test(timeout = 30_000) fun clearGlassTransmitsAndTracksChangingBackdrop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backgroundMode = mutableStateOf(0)
        val bounds = AtomicReference<Rect>()
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
                                if (backgroundMode.value == 0) drawRect(MobbyColors.Dark.Conversation.canvas)
                                else {
                                    drawRect(if (backgroundMode.value == 2) Color.White else Color.Black)
                                    drawRect(if (backgroundMode.value == 2) Color.Black else Color.White,
                                        topLeft = Offset(size.width / 2f, 0f))
                                }
                            }
                            CompositionLocalProvider(LocalConversationBackdrop provides backdrop) {
                                GlassSurface(Modifier.align(Alignment.Center).size(240.dp, 80.dp)
                                    .onGloballyPositioned {
                                        val r = it.boundsInWindow()
                                        bounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
                                    }) {}
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
                        screenshot.recycle()
                        contrast = (right - left) * sign
                        if (contrast > 0.60f) return
                    } else screenshot?.recycle()
                    SystemClock.sleep(100)
                }
                assertTrue("GPU glass must transmit and track backdrop contrast; sign=$sign contrast=$contrast", contrast > 0.60f)
            }
            // Bands sit on the left half of the top edge and right half of the bottom edge.
            val deadline = SystemClock.uptimeMillis() + 5_000
            var positionedHighlights = false
            var samples = "no frame"
            val inset = (2f * instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
            while (SystemClock.uptimeMillis() < deadline && !positionedHighlights) {
                instrumentation.waitForIdleSync()
                val r = bounds.get()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (r != null && screenshot != null) {
                    val left = r.left + (r.width() * 0.28f).roundToInt()
                    val right = r.left + (r.width() * 0.72f).roundToInt()
                    val topLeft = Color(screenshot.getPixel(left, r.top + inset)).luminance()
                    val topRight = Color(screenshot.getPixel(right, r.top + inset)).luminance()
                    val bottomLeft = Color(screenshot.getPixel(left, r.bottom - 1 - inset)).luminance()
                    val bottomRight = Color(screenshot.getPixel(right, r.bottom - 1 - inset)).luminance()
                    samples = "top=$topLeft/$topRight bottom=$bottomLeft/$bottomRight"
                    positionedHighlights = topLeft > topRight + 0.002f && bottomRight > bottomLeft + 0.002f
                }
                screenshot?.recycle()
                if (!positionedHighlights) SystemClock.sleep(100)
            }
            assertTrue("glass bands must extend inside top-left and bottom-right edge halves: $samples", positionedHighlights)
            instrumentation.runOnMainSync { backgroundMode.value = 1 }
            waitForContrast(1)
            instrumentation.runOnMainSync { backgroundMode.value = 2 }
            waitForContrast(-1)
        } finally {
            fixture?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.removeMonitor(monitor)

        }
    }
}
