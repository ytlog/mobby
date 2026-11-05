package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.os.Looper
import org.junit.Assert.assertNotEquals
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarkdownRenderingTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `reply parsing never blocks the main UI thread`() {
        compose.setContent { CompositionLocalProvider(LocalReplyParser provides { source ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            ReplyMarkdown.parse(source)
        }) { MaterialTheme { ReplyContent("reply") { _, _ -> } } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("reply").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun `blocked parsing leaves UI responsive and superseded source never replaces latest reply`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val source = mutableStateOf("old reply")
        try {
            compose.setContent { CompositionLocalProvider(LocalReplyParser provides { text ->
                if (text == "old reply") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                ReplyMarkdown.parse(text)
            }) { MaterialTheme { ReplyContent(source.value) { _, _ -> } } } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("正在排版…").assertExists()
            compose.runOnIdle { source.value = "latest reply" }
            compose.waitForIdle()
            release.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("latest reply").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("old reply").assertDoesNotExist()
            compose.onNodeWithText("正在排版…").assertDoesNotExist()
        } finally { release.countDown() }
    }
    @Test fun `pending markdown update retains the previous layout without a temporary tail or label`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = mutableStateOf("First paragraph")
        try {
            compose.setContent { CompositionLocalProvider(LocalReplyParser provides { text ->
                if (text.endsWith("continued")) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                ReplyMarkdown.parse(text)
            }) { MaterialTheme { ReplyContent(source.value) { _, _ -> } } } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("First paragraph").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { source.value = "First paragraph continued" }
            compose.waitForIdle()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.onNodeWithText("First paragraph").assertExists()
            compose.onNodeWithText(" continued").assertDoesNotExist()
            compose.onNodeWithText(UiStrings.updatingLayout).assertDoesNotExist()
            release.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("First paragraph continued").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("First paragraph").assertDoesNotExist()
        } finally { release.countDown() }
    }
    @Test fun `task list markers remain visible with their content`() {
        compose.setContent { MaterialTheme { ReplyContent("- [x] done\n- [ ] pending") { _, _ -> } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("done").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("☑").assertExists()
        compose.onNodeWithText("☐").assertExists()
        compose.onNodeWithText("done").assertExists()
        compose.onNodeWithText("pending").assertExists()
    }
    /** On the tablet a two-column table stopped at 360 dp inside a full-width border, and a long cell stood taller than its neighbour. */
    @Config(qualifiers = "w1000dp-h800dp")
    @Test fun `a narrow table fills the reply width and each row's cells are equally tall`() {
        compose.setContent { MaterialTheme { Box(Modifier.width(600.dp).testTag("reply")) {
            ReplyContent("| Name | Note |\n|---|---|\n| x | a much longer note that has to wrap onto a few lines in its column |") { _, _ -> }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("markdown-table-cell", useUnmergedTree = true).fetchSemanticsNodes().size == 4 }
        val reply = compose.onNodeWithTag("reply").fetchSemanticsNode().boundsInRoot
        val cells = compose.onAllNodesWithTag("markdown-table-cell", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(reply.right, cells[1].right, 1f)
        assertEquals(reply.right, cells[3].right, 1f)
        assertEquals(cells[2].height, cells[3].height, 0.5f)
        assertEquals(cells[0].left, reply.left, 1f)
    }
    @Test fun `table columns stretch to fill, shrink toward their floor, and scroll only past it`() {
        assertEquals(listOf(200, 400), tableColumnWidths(listOf(100, 200), listOf(100, 120), 600))
        // Too wide: each column gives up its share above its floor.
        assertEquals(listOf(100, 200), tableColumnWidths(listOf(100, 400), listOf(100, 120), 300))
        assertEquals(listOf(100, 120), tableColumnWidths(listOf(300, 400), listOf(100, 120), 200))
        assertEquals(600, tableColumnWidths(listOf(33, 33, 34), listOf(33, 33, 34), 600).sum())
    }
    @Test fun `four backtick skill document remains one code block with nested code intact`() {
        var copied: androidx.compose.ui.text.AnnotatedString? = null
        val clipboard = object : androidx.compose.ui.platform.ClipboardManager {
            override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) { copied = annotatedString }
            override fun getText() = copied
        }
        compose.setContent { MaterialTheme { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalClipboardManager provides clipboard) {
            ReplyContent("````SKILL.md\n# Workflow\n```sh\necho hi\n```\n````") { _, _ -> }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("复制原始代码").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("复制原始代码").assertCountEquals(1)
        // The block does not draw an empty last line, but copying keeps the original text exactly.
        compose.onNodeWithText("# Workflow\n```sh\necho hi\n```").assertExists()
        compose.onNodeWithContentDescription("复制原始代码").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("# Workflow\n```sh\necho hi\n```\n", copied?.text) }
    }
}
