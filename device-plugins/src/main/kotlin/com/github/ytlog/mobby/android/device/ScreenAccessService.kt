package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScreenAccessService : AccessibilityService() {
    private val stay = ScreenStay(ServiceDisplay())

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        stay.drop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal fun operate(action: String, args: Map<String, String>): String = when (action) {
        "tap" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull())
        else -> onMain {
            when (action) {
                "snapshot" -> snapshot()
                "click" -> click(args["query"].orEmpty())
                "type" -> type(args["text"].orEmpty())
                "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) AppStrings.wentBack else AppStrings.couldNotGoBack
                "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) AppStrings.returnedToHomeScreen else AppStrings.couldNotReturnToHomeScreen
                "recents" -> if (performGlobalAction(GLOBAL_ACTION_RECENTS)) AppStrings.openedRecentApps else AppStrings.couldNotOpenRecentApps
                else -> error(AppStrings.unsupportedOperation2(action))
            }
        }
    }

    private fun snapshot(): String {
        val root = rootInActiveWindow ?: return AppStrings.noReadableWindowMakeSureAccessibilityIsEnabledAnd
        return try { buildString { dump(root, 0, 0) } } finally { root.recycle() }
    }

    private fun StringBuilder.dump(node: AccessibilityNodeInfo, depth: Int, seen: Int): Int {
        var count = seen
        if (count >= 150 || depth > 12) return count
        val text = if (node.isPassword) "[secure]" else node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }.take(80)
        val clickable = if (node.isClickable) " clickable" else ""
        val focused = if (node.isFocused) " focused" else ""
        append("  ".repeat(depth)).append(node.className?.toString()?.substringAfterLast('.') ?: "View")
        if (text.isNotBlank()) append(" \"").append(text.replace("\"", "'")).append('"')
        append(clickable).append(focused).append('\n')
        count++
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            count = dump(child, depth + 1, count)
            child.recycle()
            if (count >= 150) break
        }
        return count
    }

    private fun click(query: String): String {
        if (query.isBlank() || query.length > 200) throw IllegalArgumentException(AppStrings.enterVisibleTextOfAtMostCharacters)
        val root = rootInActiveWindow ?: throw IllegalStateException(AppStrings.noWindowAvailableToClick)
        val match = find(root, query)
        val clicked = match != null && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        root.recycle(); match?.recycle()
        return if (clicked) AppStrings.clicked(query) else AppStrings.noClickableFound(query)
    }

    private fun type(text: String): String {
        if (text.isBlank() || text.length > 2000) throw IllegalArgumentException(AppStrings.enterTextOfAtMostCharacters2)
        val root = rootInActiveWindow ?: throw IllegalStateException(AppStrings.noWindowAvailableForInput)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        root.recycle(); focused?.recycle()
        return if (ok) AppStrings.enteredCharacters(text.length) else AppStrings.noFocusedInputFound
    }

    private fun tap(x: Float?, y: Float?): String {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        if (x == null || y == null || x !in 0f..1f || y !in 0f..1f) throw IllegalArgumentException(AppStrings.coordinatesMustBeProportionsBetweenAnd)
        val path = Path().apply { moveTo(x * width, y * height) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val done = CountDownLatch(1)
        val ok = AtomicReference(false)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, null)
        done.await(2, TimeUnit.SECONDS)
        return if (ok.get()) AppStrings.tappedScreenPosition else AppStrings.gestureDidNotComplete
    }

    private fun find(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        if ((text.contains(query, true) || description.contains(query, true)) && (node.isClickable || node.isEnabled)) {
            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            while (current != null && !current.isClickable) {
                val parent = current.parent
                if (current !== node) current.recycle()
                current = parent
            }
            if (current?.isClickable == true) return current
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = find(child, query)
            child.recycle()
            if (match != null) return match
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findEditable(child)
            child.recycle()
            if (match != null) return match
        }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        Handler(Looper.getMainLooper()).post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(8, TimeUnit.SECONDS)) error(AppStrings.accessibilityOperationTimedOut)
        return result.get().getOrThrow()
    }

    private inner class ServiceDisplay : DisplayHold {
        private var wake: PowerManager.WakeLock? = null
        private var overlay: View? = null

        override fun hold() = onMain {
            @Suppress("DEPRECATION")
            val lock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "mobby:screen",
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wake = lock
            try {
                val view = View(this@ScreenAccessService).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val params = WindowManager.LayoutParams(
                    1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT,
                )
                params.gravity = Gravity.START or Gravity.TOP
                getSystemService(WindowManager::class.java).addView(view, params)
                overlay = view
            } catch (error: RuntimeException) {
                lock.release()
                wake = null
                throw error
            }
        }

        override fun release() = onMain {
            overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
            overlay = null
            wake?.let { if (it.isHeld) it.release() }
            wake = null
        }
    }

    companion object {
        @Volatile internal var instance: ScreenAccessService? = null
        fun connected() = instance != null
        internal fun stay(): AutoCloseable {
            val service = instance ?: error(AppStrings.accessibilityIsOffEnableMobbySScreenServiceIn)
            return service.stay.acquire()
        }
    }
}
