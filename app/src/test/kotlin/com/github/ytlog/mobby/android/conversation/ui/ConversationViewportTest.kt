package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w850dp-h400dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationViewportTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var origin = Offset.Zero
    private var size = IntSize.Zero

    @Before fun mountViewport() {
        compose.setContent {
            val density = LocalDensity.current
            view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ConversationViewport {
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        origin = it.positionInRoot()
                        size = it.size
                    })
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `landscape cutouts reserve space on either edge after inset changes`() {
        dispatch(left = 90, top = 90)
        assertContent(left = 90, top = 90)
        dispatch(right = 90, top = 90)
        assertContent(right = 90, top = 90)
        dispatch(top = 90)
        assertContent(top = 90)
    }

    @Test fun `keyboard replaces overlapping navigation padding and restores it when hidden`() {
        dispatch(left = 90, top = 90, navigation = 60, ime = 180)
        assertContent(left = 90, top = 90, bottom = 180)
        dispatch(left = 90, top = 90, navigation = 60)
        assertContent(left = 90, top = 90, bottom = 60)
    }

    private fun dispatch(left: Int = 0, right: Int = 0, top: Int = 0, navigation: Int = 0, ime: Int = 0) {
        compose.runOnIdle {
            val cutout = DisplayCutoutCompat(Rect(left, 0, right, 0), buildList {
                if (left > 0) add(Rect(0, 0, left, 90))
                if (right > 0) add(Rect(view.width - right, 0, view.width, 90))
            })
            val insets = WindowInsetsCompat.Builder()
                .setDisplayCutout(cutout)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left, 0, right, 0))
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigation))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsetsCompat.Type.statusBars(), top > 0)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), navigation > 0)
                .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
    }

    private fun assertContent(left: Int = 0, right: Int = 0, top: Int = 0, bottom: Int = 0) {
        compose.runOnIdle {
            assertEquals("content left", left.toFloat(), origin.x, 0.5f)
            assertEquals("content top", top.toFloat(), origin.y, 0.5f)
            assertEquals("content right", (view.width - right).toFloat(), origin.x + size.width, 0.5f)
            assertEquals("content bottom", (view.height - bottom).toFloat(), origin.y + size.height, 0.5f)
        }
    }
}
