package com.github.ytlog.mobby.android.device

import org.junit.Assert.*
import org.junit.Test

class WindowOutlineTest {
    private class Fake(
        val name: String = "android.widget.FrameLayout",
        val text: String? = null,
        val description: String? = null,
        val hint: String? = null,
        val state: String? = null,
        val error: String? = null,
        val enabled: Boolean = true,
        val bounds: IntArray = intArrayOf(0, 0, 0, 0),
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
        override fun hint() = hint
        override fun state() = state
        override fun error() = error
        override fun enabled() = enabled
        override fun bounds() = bounds
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

    private fun outline(root: WindowOutline.Node, redact: (String) -> String = { it }) = WindowOutline.of(root, 720, 1280) { redact(it) }
    /** Lines without the trailing area, for tests about what is listed. */
    private fun lines(text: String) = text.lines().filter(String::isNotBlank).map { it.substringBeforeLast(" [") }

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

    @Test fun `long text and descriptions are cut and say how much is missing`() {
        val body = "评".repeat(WindowOutline.MAX_TEXT) + "😀".repeat(20)
        val root = Fake(children = listOf(
            Fake("android.widget.TextView", text = body, description = "x".repeat(WindowOutline.MAX_TEXT + 3)),
            Fake("android.widget.TextView", text = "y".repeat(WindowOutline.MAX_TEXT)),
        ))
        val (long, exact) = lines(outline(root))
        assertEquals("TextView \"" + "评".repeat(WindowOutline.MAX_TEXT) + "…(+20 chars)\" desc=\"" +
            "x".repeat(WindowOutline.MAX_TEXT) + "…(+3 chars)\"", long)
        assertEquals("TextView \"" + "y".repeat(WindowOutline.MAX_TEXT) + "\"", exact)
        // Emoji are whole code points: a cut never splits a surrogate pair.
        val emoji = WindowOutline.shorten("😀".repeat(WindowOutline.MAX_TEXT + 1))
        assertEquals("😀".repeat(WindowOutline.MAX_TEXT) + "…(+1 chars)", emoji)
    }

    @Test fun `a private value is hidden before a long text is cut`() {
        val code = "482913"
        val text = "a".repeat(WindowOutline.MAX_TEXT - 3) + code
        val outline = outline(Fake(children = listOf(Fake("android.widget.TextView", text = text)))) { it.replace(code, "[secure]") }
        assertFalse(outline.contains("482"))
    }

    @Test fun `each line ends with its area in the units tap takes, clipped to the display`() {
        val root = Fake(children = listOf(
            Fake("android.widget.ImageButton", description = "更多", clickable = true, bounds = intArrayOf(648, 64, 720, 140)),
            Fake("android.widget.TextView", text = "半露在外", bounds = intArrayOf(-36, 1250, 360, 1400)),
        ))
        assertEquals(listOf(
            "ImageButton desc=\"更多\" clickable [0.900,0.050,1.000,0.109]",
            "TextView \"半露在外\" [0.000,0.977,0.500,1.000]",
        ), outline(root).lines().filter(String::isNotBlank))
    }

    @Test fun `hints, states, errors and disabled controls are shown`() {
        val root = Fake(children = listOf(
            Fake("android.widget.EditText", hint = "搜索商品", editable = true, clickable = true),
            Fake("android.widget.Switch", state = "已开启", clickable = true),
            Fake("android.widget.EditText", text = "138", error = "手机号格式不对", editable = true),
            Fake("android.widget.Button", text = "提交", clickable = true, enabled = false),
            Fake("android.view.View", state = "3/10"),
        ))
        assertEquals(listOf(
            "EditText hint=\"搜索商品\" clickable editable",
            "Switch state=\"已开启\" clickable",
            "EditText \"138\" error=\"手机号格式不对\" editable",
            "Button \"提交\" clickable disabled",
            "View state=\"3/10\"",
        ), lines(outline(root)))
    }

    @Test fun `windows are read down to the focused one, or past popups to the first page when none has focus`() {
        fun read(vararg windows: String): List<Int> = WindowOutline.readable(
            BooleanArray(windows.size) { windows[it] != "system" },
            BooleanArray(windows.size) { windows[it].endsWith("focused") },
            BooleanArray(windows.size) { windows[it].startsWith("popup") },
        ).toList()
        // A bubble that takes no focus covers the focused page without replacing it.
        assertEquals(listOf(0, 1), read("popup", "page focused", "page"))
        assertEquals(listOf(1, 2), read("system", "popup", "page focused"))
        // A focused dialog hides the page behind it.
        assertEquals(listOf(0), read("dialog focused", "page"))
        // Without focus information a popup still shows the page, and a top-level dialog still ends the stack.
        assertEquals(listOf(0, 1), read("popup", "page", "page"))
        assertEquals(listOf(0), read("dialog", "page"))
        assertEquals(emptyList<Int>(), read("system"))
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
