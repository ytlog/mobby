package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerActionStyleTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderedView: View

    @Test fun `send and stop remain distinct from composer in both themes`() {
        val events = mutableListOf<String>()
        compose.setContent {
            val host = LocalView.current
            SideEffect { renderedView = host }
            Column(Modifier.testTag("composer-actions")) {
                for ((theme, scheme) in listOf("light" to MobbyLightScheme, "dark" to MobbyDarkScheme)) {
                    MaterialTheme(colorScheme = scheme) {
                        Surface(color = buttonColor()) {
                            Row {
                                for ((name, icon) in listOf("send" to AppIcons.Send, "stop" to AppIcons.Stop)) {
                                    for (enabled in listOf(true, false)) {
                                        val tag = "$theme-$name-$enabled"
                                        Box(Modifier.testTag(tag)) {
                                            ActionIcon(tag, { events += tag }, icon, enabled = enabled, filled = true,
                                                glyphSize = if (name == "stop") 16.dp else 20.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            for (name in listOf("send", "stop")) {
                val enabledTag = "$theme-$name-true"
                val image = snapshot(enabledTag).asImageBitmap()
                val pixels = image.toPixelMap()
                val background = pixels[2, 2].luminance()
                val circle = pixels[image.width / 4, image.height / 2].luminance()
                assertTrue("$theme $name needs a visible circular surface", contrast(background, circle) > 3f)
                if (name == "stop") {
                    val symbol = pixels[image.width / 2, image.height / 2].luminance()
                    assertTrue("$theme stop symbol must contrast with its circle", contrast(circle, symbol) > 4.5f)
                }
                val disabledTag = "$theme-$name-false"
                compose.onNodeWithContentDescription(disabledTag).assertIsNotEnabled()
                val disabled = snapshot(disabledTag).asImageBitmap().toPixelMap()
                assertTrue("Disabled surface should be subdued", contrast(background, disabled[image.width / 4, image.height / 2].luminance()) < contrast(background, circle))
                compose.onNodeWithContentDescription(enabledTag).performClick()
            }
        }
        assertEquals(listOf("light-send-true", "light-stop-true", "dark-send-true", "dark-stop-true"), events)
        System.getenv("MOBBY_ACTION_PREVIEW_DIR")?.let { path ->
            File(path).mkdirs()
            File(path, "composer-actions.png").outputStream().use {
                snapshot("composer-actions")
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun snapshot(tag: String): Bitmap {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            renderedView.draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            bitmap
        }
    }

    private fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}
