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
        val longClickable: Boolean = false,
        val editable: Boolean = false,
        val scrollable: Boolean = false,
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
        override fun longClickable() = longClickable
        override fun editable() = editable
        override fun scrollable() = scrollable
        override fun checkable() = false
        override fun checked() = false
        override fun selected() = false
        override fun focused() = false
        override fun password() = password
        override fun childCount() = children.size
        override fun child(index: Int): WindowOutline.Node = children[index].also { it.obtained++ }
        override fun release() { released++ }
        fun all(): List<Fake> = listOf(this) + children.flatMap { it.all() }
    }

    private fun outline(root: WindowOutline.Node, redact: (String) -> String = { it }) = WindowOutline.of(root) { redact(it) }
    private fun lines(text: String) = text.lines().filter(String::isNotBlank)

    @Test fun `every on-screen node at any depth and count is listed`() {
        // Compose and React Native pages nest real controls far below any fixed depth; the former limit stopped at 12.
        var leaf = Fake("android.widget.Button", text = "发布", clickable = true)
        repeat(500) { leaf = Fake(children = listOf(leaf)) }
        assertEquals(listOf("Button \"发布\" clickable"), lines(outline(leaf)))
        val items = List(1000) { Fake("android.widget.TextView", text = "item $it") }
        val text = outline(Fake(children = List(400) { Fake() } + items))
        assertEquals(1000, lines(text).size)
        assertTrue(text.contains("TextView \"item 999\""))
    }

    @Test fun `a line is given to text, descriptions, images and actions only`() {
        val root = Fake(scrollable = true, name = "androidx.recyclerview.widget.RecyclerView", children = listOf(
            Fake("android.widget.LinearLayout", children = listOf(
                Fake("android.widget.ImageView"),
                Fake("android.widget.TextView", text = "  ", description = "头像"),
                Fake("android.view.View", longClickable = true),
                Fake("android.widget.TextView", text = "第一行\n第二行", description = "第一行\n第二行"),
                Fake("android.widget.FrameLayout"),
            )),
        ))
        assertEquals(listOf(
            "RecyclerView scrollable",
            "  ImageView",
            "  TextView desc=\"头像\"",
            "  View long-clickable",
            "  TextView \"第一行\\n第二行\"",
        ), lines(outline(root)))
    }

    @Test fun `nodes off screen are skipped with their subtrees and every child read is released`() {
        val hidden = Fake("android.widget.LinearLayout", visible = false,
            children = listOf(Fake("android.widget.TextView", text = "上一页的标题")))
        val shown = Fake("android.widget.TextView", text = "返回", description = "返回上一页", clickable = true)
        val root = Fake(children = listOf(hidden, shown))
        assertEquals(listOf("TextView \"返回\" desc=\"返回上一页\" clickable"), lines(outline(root)))
        root.all().drop(1).forEach { assertEquals(it.obtained, it.released) }
        assertEquals(0, root.released)
    }

    @Test fun `secret values never appear`() {
        val root = Fake(children = listOf(
            Fake("android.widget.EditText", text = "hunter2", editable = true, password = true),
            Fake("android.widget.TextView", text = "验证码 482913 已发送"),
        ))
        val text = outline(root) { it.replace("482913", "[secure]") }
        assertTrue(text.contains("EditText \"[secure]\" editable"))
        assertFalse(text.contains("hunter2") || text.contains("482913"))
    }
}
