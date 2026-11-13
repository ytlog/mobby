package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Rect
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
import android.view.accessibility.AccessibilityWindowInfo
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
    enum class AdbSetupDestination { VERSION, WIRELESS, PAIR }

    internal val stay = ScreenStay(ServiceDisplay())
    private val adbSettings = AdbSettingsAssistant(this)

    override fun onCreate() {
        super.onCreate()
        // The framework may create and bind the service before delivering its connection callback.
        instance = this
    }
    override fun onServiceConnected() {
        instance = this
        reportContent(ScreenEvents.wantContent)
        ScreenEvents.access?.invoke(true)
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        event ?: return
        ScreenEvents.observe(packageName, event.eventType, event.packageName?.toString(), event.className?.toString(), System.currentTimeMillis())
    }

    /** Content changes are frequent; they are asked for only while a program subscribes to them. */
    internal fun reportContent(content: Boolean) {
        val info = serviceInfo ?: return
        val base = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
        info.eventTypes = if (content) base or android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else base
        serviceInfo = info
    }
    override fun onInterrupt() { adbSettings.stop() }
    override fun onDestroy() {
        adbSettings.stop()
        stay.drop()
        if (instance === this) { instance = null; ScreenEvents.access?.invoke(false) }
        super.onDestroy()
    }

    internal data class Observation(val text: String, val packageName: String, val observedAtEpochMillis: Long,
        val screenshot: ScreenScreenshot, val backend: String = "current", val displayId: Int = Display.DEFAULT_DISPLAY)

    /** Fresh, text-free page evidence for workflow matching. The full node tree is never retained. */
    internal fun inspect(query: String? = null, expectedInput: String? = null): ScreenEvidence = ScreenOperation.run {
        val layers = layers()
        try {
            val shape = StringBuilder()
            var editable = 0
            var soleInput: String? = null
            var solePassword = false
            // Every node on screen, with no node or depth limit; off-screen pages are left out as in the snapshot.
            fun walk(node: AccessibilityNodeInfo) {
                if (!node.isVisibleToUser) return
                shape.append(node.className).append(':').append(node.isClickable).append(':')
                    .append(node.isEditable).append(':').append(node.childCount).append(';')
                if (node.isEditable) { editable++; soleInput = node.text?.toString(); solePassword = node.isPassword }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { walk(child) } finally { child.recycle() }
                }
            }
            layers.forEach { shape.append('|'); walk(it.root) }
            val focused = inputFocus(layers)
            val focusedEditable = focused?.isEditable == true
            val actualInput = if (focusedEditable) focused?.text?.toString() else if (editable == 1) soleInput else null
            val passwordInput = if (focusedEditable) focused?.isPassword == true else editable == 1 && solePassword
            focused?.recycle()
            val digest = MessageDigest.getInstance("SHA-256").digest(shape.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val matches = if (query == null) 0 else findTargets(layers, query).let { targets ->
                try { targets.size } finally { targets.forEach { it.recycle() } }
            }
            ScreenEvidence(layers.last().root.packageName?.toString().orEmpty(), digest, matches, editable,
                focusedEditable, expectedInput != null && actualInput == expectedInput, passwordInput)
        } finally { release(layers) }
    }

    /** A window the agent reads, with its area on the display. */
    private class Layer(val root: AccessibilityNodeInfo, val bounds: Rect, val focused: Boolean)

    private fun release(layers: List<Layer>) = layers.forEach { it.root.recycle() }

    /**
     * The windows the agent reads, topmost first. While an application window is active, the application windows from the
     * top down to it are read, so a popup that takes no focus, such as a guide bubble or an ad layer, is read with the page it
     * covers (see [WindowOutline.readable]). While a system window such as the notification shade is active, only it is read.
     */
    private fun layers(): List<Layer> {
        val windows = windows.sortedByDescending { it.layer }
        try {
            val active = windows.firstOrNull { it.isActive }
            if (active == null || active.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = rootInActiveWindow ?: error(AppStrings.noReadableWindowMakeSureAccessibilityIsEnabledAnd)
                return listOf(Layer(root, Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels), true))
            }
            val application = BooleanArray(windows.size) { windows[it].type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val focused = BooleanArray(windows.size) { windows[it].isActive || windows[it].isFocused }
            val child = BooleanArray(windows.size) { windows[it].parent?.also { parent -> parent.recycle() } != null }
            val layers = WindowOutline.readable(application, focused, child).toList().mapNotNull { index ->
                windows[index].root?.let { Layer(it, Rect().also(windows[index]::getBoundsInScreen), focused[index]) }
            }
            if (layers.isEmpty()) error(AppStrings.noReadableWindowMakeSureAccessibilityIsEnabledAnd)
            return layers
        } finally { windows.forEach { it.recycle() } }
    }

    /** Typing goes to the input focus of the active window; a covering popup that takes no focus has none. */
    private fun inputFocus(layers: List<Layer>): AccessibilityNodeInfo? =
        layers.firstNotNullOfOrNull { it.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    internal fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit = {}): Observation =
        ScreenOperation.run(checkActive) {
            // Each operation obtains fresh nodes only after the application overlay is detached.
            val text = when (action) {
                "snapshot" -> snapshot()
                "apps" -> packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.packageName }.distinct().sorted().joinToString("\n")
                "launch" -> {
                    val name = args.getValue("package")
                    require(name != packageName)
                    val intent = packageManager.getLaunchIntentForPackage(name) ?: error("Application has no launcher")
                    startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Launch requested for $name"
                }
                "click" -> click(args["query"].orEmpty())
                "type" -> type(args["text"].orEmpty())
                "tap" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull())
                "swipe" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull(), args["toX"]?.toFloatOrNull(), args["toY"]?.toFloatOrNull())
                "long_press" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull(), duration = WindowOutline.LONG_PRESS_MILLIS.toLong())
                "back" -> global(GLOBAL_ACTION_BACK, AppStrings.wentBack, AppStrings.couldNotGoBack)
                "home" -> global(GLOBAL_ACTION_HOME, AppStrings.returnedToHomeScreen, AppStrings.couldNotReturnToHomeScreen)
                "recents" -> global(GLOBAL_ACTION_RECENTS, AppStrings.openedRecentApps, AppStrings.couldNotOpenRecentApps)
                // Stopping another app needs the shell or root identity of a virtual-display backend; the app UID has neither.
                "force_stop" -> deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY, AppStrings.forceStopNeedsVirtualScreen)
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

    /** A short touch at screen pixel ([x], [y]) for programs; waits for the system to finish or cancel the gesture. */
    internal fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val done = CountDownLatch(1)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { completed.set(true); done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, Handler(Looper.getMainLooper()))
        return accepted && done.await(2, TimeUnit.SECONDS) && completed.get()
    }

    private fun global(action: Int, success: String, failure: String): String {
        check(performGlobalAction(action)) { failure }
        return success
    }

    private fun snapshot(): String {
        val layers = layers()
        // The same units as tap and swipe, which scale fractions by these metrics.
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        return try {
            buildString {
                layers.forEachIndexed { index, layer ->
                    if (layers.size > 1) append(WindowOutline.header(index, layers.size, layer.root.packageName, layer.focused))
                    append(WindowOutline.of(layer.root, width, height) { it })
                }
            }
        } finally { release(layers) }
    }

    private fun click(query: String): String {
        if (query.isBlank() || query.length > 200) throw IllegalArgumentException(AppStrings.enterVisibleTextOfAtMostCharacters)
        val layers = layers()
        val matches = try { findTargets(layers, query) } finally { release(layers) }
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
        val layers = layers()
        // The focused field, or the only visible, enabled field: never a hidden, disabled or guessed one of several.
        val field = try {
            inputFocus(layers)?.takeIf { it.isEditable || run { it.recycle(); false } }
                ?: WindowOutline.editableFields(layers.map { it.root }).let { fields ->
                    if (fields.size == 1) fields.single() else { fields.forEach { it.recycle() }; null }
                }
        } finally { release(layers) }
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field != null && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        field?.recycle()
        check(ok) { AppStrings.noFocusedInputFound }
        return AppStrings.enteredCharacters(text.length)
    }

    private suspend fun tap(x: Float?, y: Float?, toX: Float? = null, toY: Float? = null, duration: Long? = null): String {
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
        if (toX != null || toY != null) {
            require(toX != null && toY != null && toX.isFinite() && toY.isFinite() && toX in 0f..1f && toY in 0f..1f)
            path.lineTo((toX * width).coerceAtMost(width - 1), (toY * height).coerceAtMost(height - 1))
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0,
            duration ?: if (toX == null) 80 else 350)).build()
        val ok = withTimeout(2_000 + (duration ?: 0)) {
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
        return when {
            duration != null -> AppStrings.longPressedScreenPosition
            toX == null -> AppStrings.tappedScreenPosition
            else -> AppStrings.swipedScreenPosition
        }
    }

    /**
     * Clickable controls on screen whose text, hint or description contains [query], across the read windows. A control whose
     * centre lies under a window above its own is covered, as it is for a finger, and is not a match.
     */
    private fun findTargets(layers: List<Layer>, query: String): List<AccessibilityNodeInfo> {
        val matches = mutableListOf<AccessibilityNodeInfo>()
        val above = mutableListOf<Rect>()
        fun walk(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            if (!node.isPassword && WindowOutline.searchable(node).contains(query, true)) {
                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                while (current != null && !WindowOutline.clickable(current)) {
                    val parent = current.parent
                    current.recycle()
                    current = parent
                }
                if (current != null) {
                    if (WindowOutline.covered(current, above) || matches.any { it == current }) current.recycle() else matches += current
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { walk(child) } finally { child.recycle() }
            }
        }
        return try {
            layers.forEach { walk(it.root); above += it.bounds }
            matches
        } catch (error: Exception) {
            matches.forEach { it.recycle() }
            throw error
        }
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

    /** Settings can list the service while Android has not bound it, for example after the system stopped it. */
    enum class Status { OFF, NOT_RUNNING, RUNNING }

    companion object {
        private val bound = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** Whether Android has bound the service in this process. */
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = bound
        @Volatile internal var instance: ScreenAccessService? = null
            set(value) { field = value; bound.value = value != null }
        fun connected() = instance != null

        /** The only accessibility check: enabled in system settings and bound in this process. */
        fun status(context: android.content.Context): Status {
            val resolver = context.contentResolver
            val expected = android.content.ComponentName(context, ScreenAccessService::class.java)
            val enabled = android.provider.Settings.Secure.getInt(resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
                android.provider.Settings.Secure.getString(resolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').mapNotNull(android.content.ComponentName::unflattenFromString).any { it == expected }
            return when {
                !enabled -> Status.OFF
                connected() -> Status.RUNNING
                else -> Status.NOT_RUNNING
            }
        }
        fun assistAdbSettings(destination: AdbSetupDestination, wizard: android.content.ComponentName?) {
            instance?.adbSettings?.start(destination, wizard)
        }
        internal fun returnFromAdbPairing(component: android.content.ComponentName) { instance?.adbSettings?.returnAfterPairing(component) }
        fun stopAdbSettingsAssist() { instance?.adbSettings?.stop() }
        /** The bound service, or a failure that says whether the user must enable it or restart it. */
        internal fun require(context: android.content.Context): ScreenAccessService = instance ?: error(
            if (status(context) == Status.NOT_RUNNING) AppStrings.accessibilityServiceNotRunning
            else AppStrings.accessibilityIsOffEnableMobbySScreenServiceIn)
    }
}

internal data class ScreenEvidence(val packageName: String, val shape: String, val matches: Int,
    val editable: Int, val focusedEditable: Boolean, val inputMatches: Boolean, val passwordInput: Boolean = false,
    val scene: String = shape)
