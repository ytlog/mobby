package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
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
    @Test fun `four backtick skill document remains one code block with nested code intact`() {
        compose.setContent { MaterialTheme { ReplyContent("````SKILL.md\n# Workflow\n```sh\necho hi\n```\n````") { _, _ -> } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("复制原始代码").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("复制原始代码").assertCountEquals(1)
        compose.onNodeWithText("# Workflow\n```sh\necho hi\n```\n").assertExists()
    }
}
