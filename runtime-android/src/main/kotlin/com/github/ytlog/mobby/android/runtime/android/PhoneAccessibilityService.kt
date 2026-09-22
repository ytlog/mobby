package com.github.ytlog.mobby.android.runtime.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    internal fun operate(action: String, args: Map<String, String>): String = when (action) {
        "tap" -> tap(args["x"]?.toFloatOrNull(), args["y"]?.toFloatOrNull())
        else -> onMain {
            when (action) {
                "snapshot" -> snapshot()
                "click" -> click(args["query"].orEmpty())
                "type" -> type(args["text"].orEmpty())
                "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "已返回" else "返回失败"
                "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "已回到桌面" else "回到桌面失败"
                "recents" -> if (performGlobalAction(GLOBAL_ACTION_RECENTS)) "已打开最近任务" else "打开最近任务失败"
                else -> error("不支持的操作：$action")
            }
        }
    }

    private fun snapshot(): String {
        val root = rootInActiveWindow ?: return "当前没有可读取的窗口。请确认系统无障碍已开启，且屏幕上有可见应用。"
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
        if (query.isBlank()) throw IllegalArgumentException("请提供要点击的可见文字")
        val root = rootInActiveWindow ?: throw IllegalStateException("当前没有可点击的窗口")
        val match = find(root, query)
        val clicked = match != null && match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        root.recycle(); match?.recycle()
        return if (clicked) "已点击：$query" else "未找到可点击的“$query”"
    }

    private fun type(text: String): String {
        if (text.isBlank()) throw IllegalArgumentException("请提供要输入的文字")
        val root = rootInActiveWindow ?: throw IllegalStateException("当前没有可输入的窗口")
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        root.recycle(); focused?.recycle()
        return if (ok) "已输入 ${text.length} 个字符" else "未找到可输入的焦点"
    }

    private fun tap(x: Float?, y: Float?): String {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        if (x == null || y == null || x !in 0f..1f || y !in 0f..1f) throw IllegalArgumentException("坐标须为 0 到 1 的比例")
        val path = Path().apply { moveTo(x * width, y * height) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        val done = CountDownLatch(1)
        val ok = AtomicReference(false)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.countDown() }
        }, null)
        done.await(2, TimeUnit.SECONDS)
        return if (ok.get()) "已点击屏幕位置" else "手势未完成"
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
        if (!latch.await(8, TimeUnit.SECONDS)) error("无障碍操作超时")
        return result.get().getOrThrow()
    }

    companion object {
        @Volatile internal var instance: PhoneAccessibilityService? = null
        fun connected() = instance != null
        internal fun operator() = PhoneOperator { action, args ->
            val service = instance ?: error("系统无障碍未开启。请在系统设置中打开 mobby 的“使用当前手机”。")
            service.operate(action, args)
        }
    }
}
