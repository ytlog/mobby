package com.github.ytlog.mobby.android.device

import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProgramScreenTest {
    private fun node(index: Int, parent: Int?, text: String? = null, clickable: Boolean = false, visible: Boolean = true,
        description: String? = null, viewId: String? = null, className: String = "android.widget.TextView") =
        ScreenNode(index, parent, className, viewId, text, description, clickable, false, visible, listOf(0, 0, 100, 100))

    @After fun reset() { ScreenEvents.listener = null; ScreenEvents.wantContent = false }

    @Test fun `a target names one control, standing for its clickable ancestor`() {
        val nodes = listOf(
            node(0, null, className = "android.widget.FrameLayout"),
            node(1, 0, clickable = true, className = "android.widget.LinearLayout"),
            node(2, 1, text = " 跳过 3 "),
            node(3, 0, text = "跳过此步骤", clickable = true),
            node(4, 0, text = "跳过", visible = false, clickable = true),
        )
        assertEquals(1, ScreenMatcher.pick(nodes, ScreenTarget(text = "跳过 3")).index)
        assertEquals(1, ScreenMatcher.pick(nodes, ScreenTarget(textContains = "跳过", className = "TextView", text = "跳过 3")).index)
        // Hidden controls never match; two visible matches are refused rather than guessed.
        assertEquals(ScreenRefusal.NOT_FOUND, runCatching { ScreenMatcher.pick(nodes, ScreenTarget(text = "跳过")) }.exceptionOrNull().let { (it as ScreenRefused).reason })
        assertEquals(ScreenRefusal.NOT_UNIQUE, (runCatching { ScreenMatcher.pick(nodes, ScreenTarget(textContains = "跳过")) }.exceptionOrNull() as ScreenRefused).reason)
        assertEquals(ScreenRefusal.NOT_FOUND, (runCatching { ScreenMatcher.pick(nodes, ScreenTarget()) }.exceptionOrNull() as ScreenRefused).reason)
        // A match with no clickable ancestor is clicked itself.
        assertEquals(2, ScreenMatcher.pick(listOf(node(0, null), node(1, 0), node(2, 1, text = "OK")), ScreenTarget(text = "OK")).index)
    }

    @Test fun `programs act one at a time and never while a chat run uses the screen`() {
        val chat = ScreenCoordinator.chatSession()
        assertEquals("chat_is_using_the_screen", (runCatching { ScreenCoordinator.programAction { } }.exceptionOrNull() as ScreenRefused).message)
        chat.close()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = thread { ScreenCoordinator.programAction { inside.countDown(); release.await(5, TimeUnit.SECONDS) } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        assertEquals(ScreenRefusal.BUSY, (runCatching { ScreenCoordinator.programAction { } }.exceptionOrNull() as ScreenRefused).reason)
        // A chat action waits for the program action under way instead of failing.
        val chatDone = CountDownLatch(1)
        thread { ScreenCoordinator.chatAction { chatDone.countDown() } }
        assertFalse(chatDone.await(200, TimeUnit.MILLISECONDS))
        release.countDown(); first.join()
        assertTrue(chatDone.await(5, TimeUnit.SECONDS))
        assertEquals(7, ScreenCoordinator.programAction { 7 })
    }

    @Test fun `window changes carry the previous app, content changes are opt-in and merged, mobby is left out`() {
        val seen = mutableListOf<ScreenChange>()
        ScreenEvents.listener = { seen += it }
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.home", "Launcher", 1)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.shop", "SplashActivity", 2)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.shop", null, 3)
        ScreenEvents.wantContent = true
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.shop", null, 10)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.shop", null, 100)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.shop", null, 10 + ScreenEvents.CONTENT_GAP_MILLIS)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "mobby", "MainActivity", 700)
        ScreenEvents.observe("mobby", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.shop", "MainActivity", 800)
        assertEquals(listOf(
            ScreenChange("com.home", "Launcher", null, false, 1),
            ScreenChange("com.shop", "SplashActivity", "com.home", false, 2),
            ScreenChange("com.shop", "SplashActivity", null, true, 10),
            ScreenChange("com.shop", "SplashActivity", null, true, 10 + ScreenEvents.CONTENT_GAP_MILLIS),
            ScreenChange("com.shop", "MainActivity", "mobby", false, 800),
        ), seen)
        assertEquals("MainActivity", ScreenEvents.windowClass("com.shop"))
    }
}
