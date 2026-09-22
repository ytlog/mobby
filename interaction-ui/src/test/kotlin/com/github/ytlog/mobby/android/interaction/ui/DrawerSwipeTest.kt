package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerSwipeTest {
    @get:Rule val compose = createComposeRule()
    private val opened = mutableStateOf(false)
    private val commits = mutableListOf<Boolean>()
    private lateinit var motion: DrawerMotion

    @Before fun mount() {
        compose.setContent {
            motion = rememberDrawerMotion(opened.value) { open -> commits += open; opened.value = open }
            val density = LocalDensity.current
            motion.width = with(density) { 200.dp.toPx() }
            Box(Modifier.fillMaxSize().testTag("content").drawerSwipe(motion, with(density) { DrawerSwipeEdge.toPx() }))
        }
        compose.waitForIdle()
    }

    private fun swipe(startX: Dp, step: Dp, steps: Int, gap: Long) {
        compose.onNodeWithTag("content").performTouchInput {
            down(Offset(startX.toPx(), centerY))
            repeat(steps) {
                advanceEventTime(gap)
                moveBy(Offset(step.toPx(), 0f))
            }
            advanceEventTime(gap)
            up()
        }
        compose.waitForIdle()
    }

    private fun open() {
        compose.runOnIdle { opened.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1f, motion.value, 0.001f) }
        commits.clear()
    }

    @Test fun `swiping right from the left edge opens the drawer`() {
        swipe(startX = 16.dp, step = 15.dp, steps = 10, gap = 16)
        compose.runOnIdle {
            assertEquals(listOf(true), commits)
            assertTrue(opened.value)
            assertEquals(1f, motion.value, 0.001f)
        }
    }

    @Test fun `swiping right beyond the edge band leaves the drawer closed`() {
        swipe(startX = DrawerSwipeEdge + 24.dp, step = 15.dp, steps = 10, gap = 16)
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), commits)
            assertFalse(opened.value)
            assertEquals(0f, motion.value, 0.001f)
        }
    }

    @Test fun `drawer follows the finger before the gesture ends`() {
        compose.onNodeWithTag("content").performTouchInput {
            down(Offset(16.dp.toPx(), centerY))
            advanceEventTime(16)
            moveBy(Offset(60.dp.toPx(), 0f))
        }
        compose.waitForIdle()
        // 60dp drag minus the touch slop consumed to recognise the gesture, over a 200dp drawer.
        compose.runOnIdle {
            assertTrue("partial progress expected: ${motion.value}", motion.value > 0.15f && motion.value < 0.3f)
            assertFalse(opened.value)
            assertEquals(emptyList<Boolean>(), commits)
        }
        compose.onNodeWithTag("content").performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test fun `short slow drag releases back to the closed drawer`() {
        swipe(startX = 16.dp, step = 6.dp, steps = 5, gap = 400)
        compose.runOnIdle {
            assertFalse(opened.value)
            assertEquals(0f, motion.value, 0.001f)
        }
    }

    @Test fun `swiping left closes the open drawer`() {
        open()
        swipe(startX = 150.dp, step = (-15).dp, steps = 10, gap = 16)
        compose.runOnIdle {
            assertEquals(listOf(false), commits)
            assertFalse(opened.value)
            assertEquals(0f, motion.value, 0.001f)
        }
    }

    @Test fun `swiping right keeps the open drawer open`() {
        open()
        swipe(startX = 150.dp, step = 15.dp, steps = 6, gap = 16)
        compose.runOnIdle {
            assertEquals(emptyList<Boolean>(), commits)
            assertTrue(opened.value)
            assertEquals(1f, motion.value, 0.001f)
        }
    }
}
