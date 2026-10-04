package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

class ScreenAccessService : AccessibilityService() {
    private val stay = ScreenStay(ServiceDisplay())

    override fun onCreate() {
        super.onCreate()
        // The framework may create and bind the service before delivering its connection callback.
        instance = this
    }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        stay.drop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal data class Observation(val text: String, val packageName: String, val observedAtEpochMillis: Long,
        val screenshot: ScreenScreenshot)

    /** Fresh, text-free page evidence for workflow matching. The full node tree is never retained. */
    internal fun inspect(query: String? = null, expectedInput: String? = null): ScreenEvidence = ScreenOperation.run {
        val root = rootInActiveWindow ?: error(AppStrings.noReadableWindowMakeSureAccessibilityIsEnabledAnd)
        try {
            val shape = StringBuilder()
            var editable = 0
            var soleInput: String? = null
            var solePassword = false
            fun walk(node: AccessibilityNodeInfo, depth: Int, seen: Int): Int {
                if (seen >= 150 || depth > 12) return seen
                shape.append(node.className).append(':').append(node.isClickable).append(':')
                    .append(node.isEditable).append(':').append(node.childCount).append(';')
                if (node.isEditable) { editable++; soleInput = node.text?.toString(); solePassword = node.isPassword }
                var count = seen + 1
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { count = walk(child, depth + 1, count) } finally { child.recycle() }
                    if (count >= 150) break
                }
                return count
            }
            walk(root, 0, 0)
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focusedEditable = focused?.isEditable == true
            val actualInput = if (focusedEditable) focused?.text?.toString() else if (editable == 1) soleInput else null
            val passwordInput = if (focusedEditable) focused?.isPassword == true else editable == 1 && solePassword
            focused?.recycle()
            val digest = MessageDigest.getInstance("SHA-256").digest(shape.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val matches = if (query == null) 0 else findTargets(root, query).let { targets ->
                try { targets.size } finally { targets.forEach { it.recycle() } }
            }
            ScreenEvidence(root.packageName?.toString().orEmpty(), digest, matches, editable,
                focusedEditable, expectedInput != null && actualInput == expectedInput, passwordInput)
        } finally { root.recycle() }
    }

    internal fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit = {}): Observation =
        ScreenOperation.run(checkActive) {
            // Each operation obtains fresh nodes only after the application overlay is detached.
            val text = when (action) {
                "snapshot" -> snapshot()
                "click" -> click(args["query"].orEmpty())
                "type" -> type(args["text"].orEmpty())
                "tap" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull())
                "back" -> global(GLOBAL_ACTION_BACK, AppStrings.wentBack, AppStrings.couldNotGoBack)
                "home" -> global(GLOBAL_ACTION_HOME, AppStrings.returnedToHomeScreen, AppStrings.couldNotReturnToHomeScreen)
                "recents" -> global(GLOBAL_ACTION_RECENTS, AppStrings.openedRecentApps, AppStrings.couldNotOpenRecentApps)
                else -> error(AppStrings.unsupportedOperation2(action))
            }
            if (action != "snapshot") delay(200)
            val packageName = rootInActiveWindow?.let { root -> try { root.packageName?.toString().orEmpty() } finally { root.recycle() } }.orEmpty()
            val screenshot = captureScreenshot()
            Observation(text, packageName, System.currentTimeMillis(), screenshot)
        }

    private suspend fun captureScreenshot(): ScreenScreenshot {
        if (Build.VERSION.SDK_INT < 30) return ScreenScreenshot(status = "unsupported_android_version")
        return try {
            withTimeoutOrNull(2_500) {
                suspendCancellableCoroutine { continuation ->
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            val capture = try {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    ?: error("Screenshot image is empty")
                                try {
                                    val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                        ?: error("Screenshot image cannot be copied")
                                    try { ScreenScreenshot(ScreenScreenshotEncoder.encode(software), "captured") }
                                    finally { software.recycle() }
                                }
                                finally { hardware.recycle() }
                            } catch (_: RuntimeException) {
                                ScreenScreenshot(status = "image_encoding_failed")
                            } finally { buffer.close() }
                            if (continuation.isActive) continuation.resume(capture)
                        }
                        override fun onFailure(errorCode: Int) {
                            val status = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "accessibility_unavailable"
                                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "protected_window"
                                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid_display"
                                ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid_window"
                                else -> "system_error_$errorCode"
                            }
                            if (continuation.isActive) continuation.resume(ScreenScreenshot(status = status))
                        }
                    })
                }
            } ?: ScreenScreenshot(status = "timeout")
        } catch (failure: RuntimeException) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            ScreenScreenshot(status = if (failure is SecurityException) "permission_denied" else "request_failed")
        }
    }

    private fun global(action: Int, success: String, failure: String): String {
        check(performGlobalAction(action)) { failure }
        return success
    }

    private fun snapshot(): String {
        val root = rootInActiveWindow ?: error(AppStrings.noReadableWindowMakeSureAccessibilityIsEnabledAnd)
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
        val matches = try { findTargets(root, query) } finally { root.recycle() }
        val clicked = try {
            if (matches.isEmpty()) deviceFailure(DeviceErrorCode.NOT_FOUND, AppStrings.noClickableFound(query))
            if (matches.size != 1) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Multiple controls match: $query")
            matches.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally { matches.forEach { it.recycle() } }
        check(clicked) { AppStrings.noClickableFound(query) }
        return AppStrings.clicked(query)
    }

    private fun type(text: String): String {
        if (text.isBlank() || text.length > 2000) throw IllegalArgumentException(AppStrings.enterTextOfAtMostCharacters2)
        val root = rootInActiveWindow ?: throw IllegalStateException(AppStrings.noWindowAvailableForInput)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        root.recycle(); focused?.recycle()
        check(ok) { AppStrings.noFocusedInputFound }
        return AppStrings.enteredCharacters(text.length)
    }

    private suspend fun tap(x: Float?, y: Float?): String {
        if (x == null || y == null || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f)
            throw IllegalArgumentException(AppStrings.coordinatesMustBeProportionsBetweenAnd)
        // Refresh the active window after detach; never retain accessibility nodes across actions.
        val root = rootInActiveWindow ?: error(AppStrings.noWindowAvailableToClick)
        val refreshed = root.refresh()
        root.recycle()
        check(refreshed) { AppStrings.noWindowAvailableToClick }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path().apply { moveTo((x * width).coerceAtMost(width - 1), (y * height).coerceAtMost(height - 1)) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val ok = withTimeout(2_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        }
        check(ok) { AppStrings.gestureDidNotComplete }
        return AppStrings.tappedScreenPosition
    }

    private fun findTargets(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo) {
            if (++visited > 500) deviceFailure(DeviceErrorCode.UNAVAILABLE, "Too many controls to match safely")
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if ((text.contains(query, true) || description.contains(query, true)) && (node.isClickable || node.isEnabled)) {
                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                while (current != null && !current.isClickable) {
                    val parent = current.parent
                    current.recycle()
                    current = parent
                }
                if (current != null) {
                    if (matches.none { it == current }) matches += current else current.recycle()
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { walk(child) } finally { child.recycle() }
            }
        }
        return try { walk(root); matches } catch (error: Exception) {
            matches.forEach { it.recycle() }
            throw error
        }
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

internal data class ScreenEvidence(val packageName: String, val shape: String, val matches: Int,
    val editable: Int, val focusedEditable: Boolean, val inputMatches: Boolean, val passwordInput: Boolean = false)
