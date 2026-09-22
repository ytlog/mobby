package com.github.ytlog.mobby.android

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an unlocked device with Android 14+ and system gesture navigation. */
@RunWith(AndroidJUnit4::class)
class DrawerGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var pointerDown = false

    @Test fun edgeBackMovesDrawerCancelsAndThenCompletes() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = instrumentation.targetContext
        assumeTrue(Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1) == 2)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitCondition("conversation or drawer visible") { bounds("打开会话抽屉") != null || bounds("关闭会话抽屉") != null }
        if (bounds("关闭会话抽屉") == null) tap(requireNotNull(bounds("打开会话抽屉")))
        awaitCondition("drawer open") { bounds("关闭会话抽屉") != null }
        SystemClock.sleep(300)
        val original = requireNotNull(bounds("关闭会话抽屉"))
        val screen = context.resources.displayMetrics
        val y = screen.heightPixels / 2f
        val width = screen.widthPixels.toFloat()
        val down = SystemClock.uptimeMillis()
        try {
            event(down, MotionEvent.ACTION_DOWN, 1f, y)
            move(down, 1f, width * .28f, y)
            awaitCondition("drawer follows first gesture segment") {
                bounds("关闭会话抽屉")?.left?.let { it < original.left - 2 } == true
            }
            val first = requireNotNull(bounds("关闭会话抽屉"))
            move(down, width * .28f, width * .44f, y)
            awaitCondition("drawer continues following gesture") {
                bounds("关闭会话抽屉")?.left?.let { it < first.left - 2 } == true
            }
            move(down, width * .44f, 1f, y)
            event(down, MotionEvent.ACTION_UP, 1f, y)
            awaitCondition("cancel restores drawer position") { bounds("关闭会话抽屉") == original }
            assertNull("cancel must keep conversation covered", bounds("打开会话抽屉"))
        } finally {
            cancelTouchIfNeeded(down, 1f, y)
        }
        val complete = SystemClock.uptimeMillis()
        try {
            event(complete, MotionEvent.ACTION_DOWN, 1f, y)
            move(complete, 1f, width * .75f, y)
            event(complete, MotionEvent.ACTION_UP, width * .75f, y)
            awaitCondition("completed back returns to conversation") {
                bounds("打开会话抽屉") != null && bounds("关闭会话抽屉") == null
            }
            assertNull(bounds("关闭会话抽屉"))
        } finally {
            cancelTouchIfNeeded(complete, width * .75f, y)
        }
    }

    private fun move(down: Long, from: Float, to: Float, y: Float) {
        for (i in 1..10) {
            SystemClock.sleep(12)
            event(down, MotionEvent.ACTION_MOVE, from + (to - from) * i / 10f, y)
        }
    }
    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        try {
            event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
            SystemClock.sleep(30)
            event(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
        } finally { cancelTouchIfNeeded(down, rect.exactCenterX(), rect.exactCenterY()) }
    }
    private fun cancelTouchIfNeeded(down: Long, x: Float, y: Float) {
        if (!pointerDown) return
        // Only clean up an unfinished touch; never replace the original test failure.
        runCatching { event(down, MotionEvent.ACTION_CANCEL, x, y) }
        pointerDown = false
    }
    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("touch event accepted", automation.injectInputEvent(event, true))
            if (action == MotionEvent.ACTION_DOWN) pointerDown = true
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerDown = false
        } finally { event.recycle() }
    }
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(description)
    }
    @Suppress("DEPRECATION")
    private fun bounds(label: String): Rect? {
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.isVisibleToUser && (node.contentDescription?.toString() == label || node.text?.toString() == label)) {
                return Rect().also(node::getBoundsInScreen)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child)?.let { return it } } finally { child.recycle() }
            }
            return null
        }
        val root = automation.rootInActiveWindow ?: return null
        return try { visit(root) } finally { root.recycle() }
    }
}
