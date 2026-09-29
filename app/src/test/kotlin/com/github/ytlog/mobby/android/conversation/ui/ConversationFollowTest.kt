package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationFollowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `growth follows gradually and pausing follow preserves reading position`() {
        val height = mutableStateOf(400)
        val following = mutableStateOf(true)
        lateinit var list: LazyListState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            list = rememberLazyListState()
            LaunchedEffect(following.value) { if (following.value) list.followConversationTail() }
            LazyColumn(Modifier.height(200.dp), state = list) {
                item("reply") { Spacer(Modifier.height(height.value.dp)) }
                item("loading") { Spacer(Modifier.height(20.dp)) }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { assertFalse(list.canScrollForward) }
        val before = list.firstVisibleItemScrollOffset
        compose.runOnIdle { height.value += 120 }
        var sawIntermediatePosition = false
        repeat(20) {
            compose.mainClock.advanceTimeBy(16)
            compose.runOnIdle {
                if (list.firstVisibleItemScrollOffset > before && list.canScrollForward) sawIntermediatePosition = true
            }
        }
        assertTrue("Follow must pass through intermediate positions instead of snapping", sawIntermediatePosition)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertFalse(list.canScrollForward); following.value = false }
        compose.mainClock.advanceTimeBy(32)
        val paused = list.firstVisibleItemScrollOffset
        compose.runOnIdle { height.value += 120 }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(paused, list.firstVisibleItemScrollOffset); assertTrue(list.canScrollForward) }
    }
}
