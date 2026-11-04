package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Test shared surface lighting without AGSL; optical backdrop effects are checked on the phone.
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassSurfaceStyleTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View

    @Test fun `clear glass retains visible depth on a plain white conversation`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = MobbyLightScheme) {
                Box(Modifier.background(Color.White)) {
                    GlassSurface(Modifier.size(240.dp, 80.dp).testTag("glass")) {}
                }
            }
        }
        val bounds = compose.onNodeWithTag("glass").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        val upper = pixels[120, 12].luminance()
        val lower = pixels[120, 68].luminance()
        assertTrue("white backdrop needs visible glass shading, upper=$upper lower=$lower", upper - lower > 0.025f)
        assertTrue("clear glass must not look like a dark opaque panel", lower > 0.75f)
    }

    @Test fun `dark glass has restrained surface lighting instead of metallic shading`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = MobbyDarkScheme) {
                Box(Modifier.background(MobbyColors.Dark.Conversation.canvas)) {
                    GlassSurface(Modifier.size(240.dp, 80.dp).testTag("dark-glass")) {}
                }
            }
        }
        val bounds = compose.onNodeWithTag("dark-glass").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        val upper = pixels[120, 12].luminance()
        val lower = pixels[120, 68].luminance()
        assertTrue("dark glass should not have a metallic light-to-dark ramp: $upper / $lower",
            kotlin.math.abs(upper - lower) < 0.025f)
        val rim = pixels[120, 0].luminance()
        assertTrue("dark rim should not look like a bright continuous outline: $rim", rim < 0.12f)
    }

    @Test fun `regular glass preserves coarse backdrop tones without being fully see-through`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            androidx.compose.foundation.Canvas(Modifier.size(240.dp, 80.dp).testTag("transmission")) {
                drawRect(Color.Black)
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f))
                drawGlassLighting(MobbyColors.Dark.button, dark = true, accented = false)
            }
        }
        val bounds = compose.onNodeWithTag("transmission").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        val transmittedContrast = pixels[180, 40].luminance() - pixels[60, 40].luminance()
        assertTrue("glass must retain coarse tones while protecting controls: $transmittedContrast", transmittedContrast in 0.25f..0.55f)
    }

    @Test fun `light neutral glass transmits broad tones without a milky fill`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            androidx.compose.foundation.Canvas(Modifier.size(240.dp, 80.dp).testTag("light-transmission")) {
                drawRect(Color.Black)
                drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f))
                drawGlassLighting(Color(0xFFE3E9F1), dark = false, accented = false)
            }
        }
        val bounds = compose.onNodeWithTag("light-transmission").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        val contrast = pixels[180, 40].luminance() - pixels[60, 40].luminance()
        assertTrue("light glass must keep broad backdrop contrast: $contrast", contrast > 0.85f)
    }

}
