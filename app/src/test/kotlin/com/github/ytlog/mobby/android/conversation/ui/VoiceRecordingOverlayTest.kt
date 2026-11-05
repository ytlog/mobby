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

    @Test fun `recording glow rises softly from the bottom on a light canvas`() = checkWash(MobbyLightScheme, "light")

    @Test fun `recording glow rises softly from the bottom on a dark canvas`() = checkWash(MobbyDarkScheme, "dark")

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
        val backdrop = image.getPixel(10, 20)
        val center = image.getPixel(500, 790)
        val aboveOverlay = image.getPixel(500, 560)
        val side = image.getPixel(170, 790)
        val farAbove = image.getPixel(500, 60)
        assertTrue("the glow must be strongest in the bottom center", distance(backdrop, center) > 40)
        assertTrue("the glow must spread above the overlay's own bounds", distance(backdrop, aboveOverlay) > 8)
        assertTrue("the glow must soften toward the sides", distance(backdrop, side) < distance(backdrop, center))
        assertTrue("the glow must fade out before the top", distance(backdrop, farAbove) < 4)
    }

    private fun distance(a: Int, b: Int) =
        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
}
