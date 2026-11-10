package com.github.ytlog.mobby.android.device

import org.junit.Assert.*
import org.junit.Test

class WindowOutlineTest {
    private class Fake(
        val name: String = "android.widget.FrameLayout",
        val text: String? = null,
        val description: String? = null,
        val visible: Boolean = true,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val password: Boolean = false,
        val children: List<Fake> = emptyList(),
    ) : WindowOutline.Node {
        var obtained = 0
        var released = 0
        override fun className() = name
        override fun text() = text
        override fun description() = description
        override fun visible() = visible
        override fun clickable() = clickable
        override fun editable() = editable
        override fun scrollable() = false
        override fun checkable() = false
        override fun checked() = false
        override fun focused() = false
        override fun password() = password
        override fun childCount() = children.size
        override fun child(index: Int): WindowOutline.Node = children[index].also { it.obtained++ }
        override fun release() { released++ }
        fun all(): List<Fake> = listOf(this) + children.flatMap { it.all() }
    }

    private fun outline(root: WindowOutline.Node, redact: (String) -> String = { it }) = WindowOutline.of(root) { redact(it) }

    @Test fun `content under deep wrappers is listed`() {
        // Compose and React Native pages nest real controls 20 and more levels down; the former limit stopped at 12.
        var leaf = Fake("android.widget.Button", text = "发布", clickable = true)
        repeat(30) { leaf = Fake(children = listOf(leaf)) }
        val text = outline(leaf)
        assertTrue(text, text.contains("Button \"发布\" clickable"))
        assertFalse(text.contains("truncated"))
        // Empty wrappers carry nothing for the agent and take no line; the window itself is named.
        assertEquals(2, text.lines().filter(String::isNotBlank).size)
    }

    @Test fun `many wrappers before the content do not use up the budget`() {
        val wrappers = List(400) { Fake() }
        val root = Fake(children = wrappers + Fake("android.widget.TextView", text = "订单已送达"))
        assertTrue(outline(root).contains("TextView \"订单已送达\""))
    }

    @Test fun `hidden subtrees are skipped and every child read is released`() {
        val hidden = Fake("android.widget.LinearLayout", visible = false,
            children = listOf(Fake("android.widget.TextView", text = "上一页的标题")))
        val shown = Fake("android.widget.TextView", text = "", description = "返回", clickable = true)
        val root = Fake(children = listOf(hidden, shown))
        val text = outline(root)
        assertFalse(text.contains("上一页的标题"))
        assertTrue(text, text.contains("TextView \"返回\" clickable"))
        root.all().drop(1).forEach { assertEquals(it.obtained, it.released) }
        assertEquals(0, root.released)
    }

    @Test fun `secret values never appear and a cut outline says so`() {
        val root = Fake(children = listOf(
            Fake("android.widget.EditText", text = "hunter2", editable = true, password = true),
            Fake("android.widget.TextView", text = "验证码 482913 已发送"),
        ) + List(WindowOutline.MAX_LINES + 5) { Fake("android.widget.TextView", text = "item $it") })
        val text = outline(root) { it.replace("482913", "[secure]") }
        assertTrue(text.contains("EditText \"[secure]\" editable"))
        assertFalse(text.contains("hunter2") || text.contains("482913"))
        assertEquals(WindowOutline.TRUNCATED, text.trimEnd().lines().last())
        assertEquals(WindowOutline.MAX_LINES + 1, text.trimEnd().lines().size)
    }
}
