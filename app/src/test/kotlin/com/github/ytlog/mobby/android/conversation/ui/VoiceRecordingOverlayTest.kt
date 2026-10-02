package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceRecordingOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `recording wash fades into light canvas at all edges`() = checkWash(MobbyLightScheme, "light")

    @Test fun `recording wash fades into dark canvas at all edges`() = checkWash(MobbyDarkScheme, "dark")

    private fun checkWash(scheme: ColorScheme, theme: String) {
        lateinit var view: View
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = scheme) {
                Box(Modifier.fillMaxSize().background(conversationCanvas())) {
                    VoiceRecordingOverlay(false, 0.8f, "", Modifier.align(Alignment.BottomCenter).width(760.dp).height(180.dp))
                }
            }
        }
        val image = compose.runOnIdle {
            Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        System.getenv("MOBBY_VOICE_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "voice-overlay-$theme.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val backdrop = image.getPixel(110, 780)
        val left = image.getPixel(122, 780)
        val center = image.getPixel(500, 780)
        val right = image.getPixel(878, 780)
        val top = image.getPixel(500, 621)
        val topBackdrop = image.getPixel(110, 621)
        assertTrue("the wash must remain visible in its center", distance(backdrop, center) > 20)
        assertTrue("left edge should blend into the canvas", distance(backdrop, left) < 8)
        assertTrue("right edge should blend into the canvas", distance(backdrop, right) < 8)
        assertTrue("top edge should blend into the canvas", distance(topBackdrop, top) < 8)
    }

    private fun distance(a: Int, b: Int) =
        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
}
