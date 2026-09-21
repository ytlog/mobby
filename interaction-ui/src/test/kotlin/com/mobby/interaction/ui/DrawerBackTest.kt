package com.mobby.interaction.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerBackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var dispatcher: OnBackPressedDispatcher
    private val opened = mutableStateOf(true)
    private var closes = 0
    private var fraction = 0f
    private lateinit var progressState: State<Float>

    @Before fun mount() {
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            progressState = rememberDrawerProgress(opened.value) { closes++; opened.value = false }
            val progress by progressState
            fraction = progress
            Box(Modifier.size(200.dp)) {
                Box(Modifier.offset { IntOffset((100 * progress).roundToInt(), 0) }.size(48.dp).testTag("conversation"))
            }
        }
        compose.waitForIdle()
    }
    private fun drag(progress: Float) {
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(50f, 0f, progress, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }
    @Test fun `cancelled back follows gesture then restores drawer without closing`() {
        drag(0.4f)
        assertEquals(60f, compose.onNodeWithTag("conversation").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.runOnIdle { assertEquals(0.6f, fraction, 0.001f); assertTrue(opened.value); assertEquals(0, closes) }
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, fraction, 0.001f); assertTrue(opened.value); assertEquals(0, closes); dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(opened.value); assertEquals(1, closes) }
    }
    @Test fun `back cancelled before the next composition frame restores the fully open drawer`() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(50f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        // Consume the back callback without giving Compose a frame to observe predicting=true.
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0.6f, progressState.value, 0.001f)
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1f, progressState.value, 0.001f)
            assertTrue(opened.value)
            assertEquals(0, closes)
        }
    }
    @Test fun `back started during opening continues from the visible drawer position`() {
        compose.runOnIdle { opened.value = false }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { opened.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        var visibleAtStart = 0f
        compose.runOnIdle {
            visibleAtStart = progressState.value
            assertTrue("opening must be in progress: $visibleAtStart", visibleAtStart > 0f && visibleAtStart < 1f)
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(50f, 0f, 0.1f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(visibleAtStart * 0.9f, progressState.value, 0.001f)
            assertEquals(0, closes)
        }
    }
    @Test fun `closing externally during a gesture leaves drawer closed without another close command`() {
        drag(0.5f)
        compose.runOnIdle { opened.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, fraction, 0.001f); assertEquals(0, closes) }
    }
    @Test fun `committed predictive back closes once and ordinary back remains supported`() {
        drag(0.75f)
        compose.runOnIdle { assertEquals(0.25f, fraction, 0.001f); dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, fraction, 0.001f); assertFalse(opened.value); assertEquals(1, closes); opened.value = true }
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, fraction, 0.001f); assertFalse(opened.value); assertEquals(2, closes) }
    }
}
