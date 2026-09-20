package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
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
    @Test fun `task list markers remain visible with their content`() {
        compose.setContent { MaterialTheme { ReplyContent("- [x] done\n- [ ] pending") { _, _ -> } } }
        compose.onNodeWithText("☑").assertExists()
        compose.onNodeWithText("☐").assertExists()
        compose.onNodeWithText("done").assertExists()
        compose.onNodeWithText("pending").assertExists()
    }
    @Test fun `four backtick skill document remains one code block with nested code intact`() {
        compose.setContent { MaterialTheme { ReplyContent("````SKILL.md\n# Workflow\n```sh\necho hi\n```\n````") { _, _ -> } } }
        compose.onAllNodesWithContentDescription("复制原始代码").assertCountEquals(1)
        compose.onNodeWithText("# Workflow\n```sh\necho hi\n```\n").assertExists()
    }
}
