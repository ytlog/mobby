package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
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

    // The user wants glass only on the home page and in the drawer.
    @Test fun `outside the home page and drawer a surface is plain and opaque`() {
        var expected = Color.Unspecified
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = MobbyLightScheme) {
                expected = conversationCardColor()
                Box(Modifier.background(Color.Black)) {
                    GlassSurface(Modifier.size(240.dp, 80.dp).testTag("plain")) {}
                }
            }
        }
        val bounds = compose.onNodeWithTag("plain").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        listOf(pixels[120, 12], pixels[120, 40], pixels[120, 68]).forEach { pixel ->
            org.junit.Assert.assertEquals(expected.red, pixel.red, 0.01f)
            org.junit.Assert.assertEquals(expected.blue, pixel.blue, 0.01f)
        }
    }

    @Test fun `a menu opened from the home page is not glass`() {
        var enabled = true
        var buttons = true
        compose.setContent {
            MaterialTheme(colorScheme = MobbyLightScheme) {
                androidx.compose.runtime.CompositionLocalProvider(LocalGlassEnabled provides true) {
                    FrostedMenu(true, {}) {
                        enabled = LocalGlassEnabled.current
                        buttons = LocalGlassControls.current
                    }
                }
            }
        }
        compose.runOnIdle {
            org.junit.Assert.assertFalse(enabled)
            org.junit.Assert.assertFalse(buttons)
        }
    }

    @Test fun `the drawer scope keeps glass on`() {
        var enabled = false
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme) { GlassControlScope(MobbyColors.Light.drawer) { enabled = LocalGlassEnabled.current } } }
        compose.runOnIdle { assertTrue(enabled) }
    }

    @Test fun `clear glass retains visible depth on a plain white conversation`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = MobbyLightScheme) {
                androidx.compose.runtime.CompositionLocalProvider(LocalGlassEnabled provides true) {
                    Box(Modifier.background(Color.White)) {
                        GlassSurface(Modifier.size(240.dp, 80.dp).testTag("glass")) {}
                    }
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
                    androidx.compose.runtime.CompositionLocalProvider(LocalGlassEnabled provides true) {
                        GlassSurface(Modifier.size(240.dp, 80.dp).testTag("dark-glass")) {}
                    }
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
                drawGlassLighting(MobbyColors.Light.glassBase, dark = false, accented = false)
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

    @Test fun `toolbar decoration can overflow over transcript without enlarging toolbar slot`() {
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            Column(Modifier.size(120.dp, 100.dp).testTag("chrome")) {
                ConversationToolbarLayer(Modifier.size(120.dp, 40.dp).testTag("header")) {
                    Box(Modifier.size(120.dp, 40.dp).drawBehind {
                        // Deterministic overflow probes draw order, independent of GPU shadow support.
                        drawRect(Color.Black, size = Size(size.width, size.height + 12.dp.toPx()))
                    })
                }
                Box(Modifier.size(120.dp, 60.dp).background(Color.White).testTag("body"))
            }
        }
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        assertEquals("header keeps its existing slot", 40f, header.height, 0.5f)
        assertEquals("transcript keeps its original origin", header.bottom, body.top, 0.5f)
        val root = compose.onNodeWithTag("chrome").fetchSemanticsNode().boundsInRoot
        val pixels = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width.toInt(), root.height.toInt(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap).apply { translate(-root.left, -root.top) })
            bitmap.asImageBitmap().toPixelMap()
        }
        assertTrue("transcript must not erase the overflowing header decoration", pixels[60, 46].luminance() < 0.1f)
        assertTrue("decoration stays local rather than covering the transcript", pixels[60, 70].luminance() > 0.9f)
    }

}
