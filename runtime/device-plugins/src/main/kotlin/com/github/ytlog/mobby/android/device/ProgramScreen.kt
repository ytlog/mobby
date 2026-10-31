package com.github.ytlog.mobby.android.device

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * One node of a window snapshot. [parent] is the index of its parent in the same snapshot; [bounds] are left, top, right,
 * bottom in thousandths of the display, so they do not depend on its resolution. Password fields read as `[secure]`.
 */
data class ScreenNode(
    val index: Int, val parent: Int?, val className: String, val viewId: String?, val text: String?, val description: String?,
    val clickable: Boolean, val editable: Boolean, val visible: Boolean, val bounds: List<Int>,
)

/** The window in front when it was read. [truncated] says nodes past the limit were left out. */
data class ScreenWindow(val packageName: String, val className: String?, val nodes: List<ScreenNode>, val truncated: Boolean, val readAt: Long)

/**
 * How a program names one control. Every given field must match: [text] and [description] after trimming, the
 * `*Contains` fields as substrings, [viewId] and [className] exactly (class names may be given without a package).
 */
data class ScreenTarget(
    val text: String? = null, val textContains: String? = null, val description: String? = null,
    val descriptionContains: String? = null, val viewId: String? = null, val className: String? = null,
) {
    val empty get() = listOf(text, textContains, description, descriptionContains, viewId, className).all { it.isNullOrEmpty() }

    fun matches(node: ScreenNode): Boolean {
        if (!node.visible || empty) return false
        val nodeText = node.text?.trim().orEmpty()
        val nodeDescription = node.description?.trim().orEmpty()
        return (text == null || nodeText == text.trim()) &&
            (textContains == null || nodeText.contains(textContains, ignoreCase = true)) &&
            (description == null || nodeDescription == description.trim()) &&
            (descriptionContains == null || nodeDescription.contains(descriptionContains, ignoreCase = true)) &&
            (viewId == null || node.viewId == viewId) &&
            (className == null || node.className == className || node.className.substringAfterLast('.') == className)
    }
}

enum class ScreenRefusal { SERVICE_OFF, NO_WINDOW, SCREEN_OFF, LOCKED, BUSY, OWN_APP, WINDOW_CHANGED, NOT_FOUND, NOT_UNIQUE, FAILED }

/** A screen request that was not carried out, with the reason; nothing was done on screen. */
class ScreenRefused(val reason: ScreenRefusal, message: String) : Exception(message)

/**
 * Who may act on the screen. A chat run using the screen plugin comes first: while one is open, programs may read but not
 * act. Programs act one at a time, and a chat action waits for a program action already under way.
 */
object ScreenCoordinator {
    private val actor = ReentrantLock(true)
    private var chatSessions = 0

    /** Held for a chat run that has the screen plugin. */
    fun chatSession(): AutoCloseable {
        synchronized(this) { chatSessions++ }
        return AutoCloseable { synchronized(this) { if (chatSessions > 0) chatSessions-- } }
    }

    val chatActive: Boolean get() = synchronized(this) { chatSessions > 0 }

    internal fun <T> chatAction(block: () -> T): T {
        if (!actor.tryLock(5, TimeUnit.SECONDS)) error("A program is still acting on the screen")
        try { return block() } finally { actor.unlock() }
    }

    fun <T> programAction(block: () -> T): T {
        if (chatActive) throw ScreenRefused(ScreenRefusal.BUSY, "chat_is_using_the_screen")
        if (!actor.tryLock()) throw ScreenRefused(ScreenRefusal.BUSY, "screen_is_busy")
        try {
            if (chatActive) throw ScreenRefused(ScreenRefusal.BUSY, "chat_is_using_the_screen")
            return block()
        } finally { actor.unlock() }
    }
}

/** Picks the one control [target] names: each match stands for itself or its nearest clickable ancestor. */
internal object ScreenMatcher {
    fun pick(nodes: List<ScreenNode>, target: ScreenTarget): ScreenNode {
        if (target.empty) throw ScreenRefused(ScreenRefusal.NOT_FOUND, "target_needs_a_field")
        val chosen = nodes.filter(target::matches).map { match ->
            var current: ScreenNode? = match
            while (current != null && !current.clickable) current = current.parent?.let(nodes::getOrNull)
            current ?: match
        }.distinctBy { it.index }
        return when (chosen.size) {
            0 -> throw ScreenRefused(ScreenRefusal.NOT_FOUND, "no_visible_control_matches")
            1 -> chosen.single()
            else -> throw ScreenRefused(ScreenRefusal.NOT_UNIQUE, "${chosen.size}_controls_match")
        }
    }
}

/**
 * Screen reading and actions for programs, through the mobby Screen Control service. Unlike the chat screen plugin it takes
 * no screenshot, hides no overlay and keeps no screen on; it never reads or acts on mobby itself, and acts only while the
 * screen is on and unlocked.
 */
object ProgramScreen {
    const val MAX_NODES = 300
    private const val MAX_DEPTH = 30
    private const val MAX_TEXT = 200
    private const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID

    fun read(context: Context): ScreenWindow = capture(context) { window, _ -> window }

    /**
     * Clicks the one control [target] names in [packageName]'s window and returns it. With [tap] the control is touched at
     * its centre instead of sent an accessibility click: on the tablet a video app's splash ad ignored the click.
     */
    fun click(context: Context, packageName: String, target: ScreenTarget, tap: Boolean = false): ScreenNode = ScreenCoordinator.programAction {
        dispatchable(context)
        val (picked, centre) = capture(context) { window, nodes ->
            if (window.packageName != packageName) throw ScreenRefused(ScreenRefusal.WINDOW_CHANGED, "window_is_${window.packageName}")
            val picked = ScreenMatcher.pick(window.nodes, target)
            val node = nodes[picked.index]
            if (tap) {
                val rect = Rect().also(node::getBoundsInScreen)
                if (rect.isEmpty) throw ScreenRefused(ScreenRefusal.FAILED, "control_has_no_area_to_tap")
                picked to (rect.exactCenterX() to rect.exactCenterY())
            } else {
                if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw ScreenRefused(ScreenRefusal.FAILED, "click_not_performed")
                picked to null
            }
        }
        // The gesture is sent after the nodes are released; the window was checked in the same read.
        centre?.let { (x, y) ->
            val service = ScreenAccessService.instance ?: throw ScreenRefused(ScreenRefusal.SERVICE_OFF, "screen_service_off")
            if (!service.tapAt(x, y)) throw ScreenRefused(ScreenRefusal.FAILED, "tap_not_completed")
        }
        picked
    }

    /** A global action, only while [packageName]'s window is in front. */
    fun global(context: Context, packageName: String, action: Int) = ScreenCoordinator.programAction {
        dispatchable(context)
        val service = ScreenAccessService.instance ?: throw ScreenRefused(ScreenRefusal.SERVICE_OFF, "screen_service_off")
        val current = service.rootInActiveWindow?.let { root -> try { root.packageName?.toString() } finally { root.recycle() } }
            ?: throw ScreenRefused(ScreenRefusal.NO_WINDOW, "no_readable_window")
        if (current == context.packageName) throw ScreenRefused(ScreenRefusal.OWN_APP, "mobby_window_is_not_for_programs")
        if (current != packageName) throw ScreenRefused(ScreenRefusal.WINDOW_CHANGED, "window_is_$current")
        if (!service.performGlobalAction(action)) throw ScreenRefused(ScreenRefusal.FAILED, "action_not_performed")
    }

    private fun dispatchable(context: Context) {
        if (!context.getSystemService(PowerManager::class.java).isInteractive) throw ScreenRefused(ScreenRefusal.SCREEN_OFF, "screen_is_off")
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw ScreenRefused(ScreenRefusal.LOCKED, "device_is_locked")
    }

    /** Reads the active window once; [use] runs while its nodes are still valid. */
    private fun <T> capture(context: Context, use: (ScreenWindow, List<AccessibilityNodeInfo>) -> T): T {
        val service = ScreenAccessService.instance ?: throw ScreenRefused(ScreenRefusal.SERVICE_OFF, "screen_service_off")
        // Every node fetch is answered on the app's main thread, which is busy while it starts. Fetched one by one, a
        // splash screen of about 220 nodes took 1-1.7 s to read on the tablet; prefetching gets them in a few requests.
        val root = (if (Build.VERSION.SDK_INT >= 33) service.getRootInActiveWindow(PREFETCH) else service.rootInActiveWindow)
            ?: throw ScreenRefused(ScreenRefusal.NO_WINDOW, "no_readable_window")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName == context.packageName) { root.recycle(); throw ScreenRefused(ScreenRefusal.OWN_APP, "mobby_window_is_not_for_programs") }
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val held = mutableListOf<AccessibilityNodeInfo>()
        val nodes = mutableListOf<ScreenNode>()
        var truncated = false
        val rect = Rect()
        /** Returns whether [node] was kept; kept nodes are recycled together at the end. */
        fun walk(node: AccessibilityNodeInfo, parent: Int?, depth: Int): Boolean {
            if (nodes.size >= MAX_NODES || depth > MAX_DEPTH) { truncated = true; return false }
            node.getBoundsInScreen(rect)
            val index = nodes.size
            nodes += ScreenNode(index, parent, node.className?.toString().orEmpty(), node.viewIdResourceName,
                if (node.isPassword) "[secure]" else node.text?.toString()?.take(MAX_TEXT),
                node.contentDescription?.toString()?.take(MAX_TEXT), node.isClickable, node.isEditable, node.isVisibleToUser,
                listOf(rect.left * 1000 / width, rect.top * 1000 / height, rect.right * 1000 / width, rect.bottom * 1000 / height))
            held += node
            for (i in 0 until node.childCount) {
                val child = (if (Build.VERSION.SDK_INT >= 33) node.getChild(i, PREFETCH) else node.getChild(i)) ?: continue
                if (!walk(child, index, depth + 1)) child.recycle()
            }
            return true
        }
        try {
            walk(root, null, 0)
            return use(ScreenWindow(packageName, ScreenEvents.windowClass(packageName), nodes, truncated, System.currentTimeMillis()), held)
        } finally { held.forEach { it.recycle() } }
    }
}

/**
 * A window coming to the front ([content] false) or its content changing. Only metadata: programs read nodes with
 * [ProgramScreen.read]. [previousPackage] is the app that was in front before, for telling an app switch from a page change.
 */
data class ScreenChange(val packageName: String, val className: String?, val previousPackage: String?, val content: Boolean, val at: Long)

/**
 * Forwards accessibility events as [ScreenChange]s. Content changes are delivered only while [wantContent] is set, at most
 * once per [CONTENT_GAP_MILLIS] for each app; mobby's own windows are never reported.
 */
object ScreenEvents {
    const val CONTENT_GAP_MILLIS = 500L
    @Volatile var listener: ((ScreenChange) -> Unit)? = null
    @Volatile var wantContent = false
        set(value) { field = value; ScreenAccessService.instance?.reportContent(value) }
    private val classes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastContent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var front: String? = null

    fun windowClass(packageName: String): String? = classes[packageName]

    /** Called on the accessibility thread; only bookkeeping and a hand-off happen here. */
    internal fun observe(ownPackage: String, type: Int, packageName: String?, className: String?, at: Long) {
        if (packageName.isNullOrEmpty()) return
        when (type) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                className?.let { classes[packageName] = it }
                val previous = front
                front = packageName
                if (packageName != ownPackage) listener?.invoke(ScreenChange(packageName, className, previous, false, at))
            }
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!wantContent || packageName == ownPackage) return
                if (at - (lastContent[packageName] ?: Long.MIN_VALUE / 2) < CONTENT_GAP_MILLIS) return
                lastContent[packageName] = at
                listener?.invoke(ScreenChange(packageName, classes[packageName], null, true, at))
            }
        }
    }
}
