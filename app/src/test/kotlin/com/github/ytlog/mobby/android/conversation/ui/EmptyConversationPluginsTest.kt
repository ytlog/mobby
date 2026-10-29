package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
class EmptyConversationPluginsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `empty conversation offers four equal entries in one row and selects their actual refs`() {
        val selected = mutableListOf<String>()
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet()) { selected += it } } }
        compose.onNodeWithText("AI 操作手机").assertIsDisplayed().performClick()
        compose.onNodeWithText("程序开发").assertIsDisplayed().performClick()
        compose.onNodeWithText("访问文件").assertIsDisplayed().performClick()
        compose.onNodeWithText("查看照片").assertIsDisplayed().performClick()
        val refs = listOf("plugin:device:screen", "plugin:device:programs", "plugin:device:storage", "plugin:device:media")
        assertEquals(refs, selected)
        val bounds = refs.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        // Weights split leftover pixels, so equal shares may differ by one pixel.
        val widths = bounds.map { it.right - it.left }
        assertTrue(widths.max() - widths.min() <= 1.dp)
        assertTrue(bounds.all { it.top == bounds.first().top })
        assertTrue(bounds.zipWithNext().all { (left, right) -> right.left >= left.right })
    }

    @Test fun `with programs the entries share one row and up to three programs open from below`() {
        val actions = mutableListOf<String>()
        val programs = EmptyPagePrograms(listOf(
            ProgramShortcut("header-watch", "Header Watch", opensPage = true),
            ProgramShortcut("price", "价格提醒", opensPage = false, note = "需要填写配置"),
            ProgramShortcut("notes", "记账本", opensPage = true),
            ProgramShortcut("fourth", "第四个", opensPage = true),
        ), openPage = { actions += "page:$it" }, openDetails = { actions += "details:$it" }, openAll = { actions += "all" })
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet(), programs) { actions += it } } }
        val refs = listOf("plugin:device:screen", "plugin:device:programs", "plugin:device:storage", "plugin:device:media")
        val tops = refs.map { compose.onNodeWithTag(it).getBoundsInRoot().top }
        assertTrue(tops.all { it == tops.first() })
        compose.onNodeWithText("第四个").assertDoesNotExist()
        compose.onNodeWithText("Header Watch").performClick()
        compose.onNodeWithText("价格提醒").performClick()
        compose.onNodeWithText(UiStrings.allProgramsShort).performClick()
        compose.onNodeWithTag("plugin:device:media").performClick()
        assertEquals(listOf("page:header-watch", "details:price", "all", "plugin:device:media"), actions)
    }

    @Test @Config(sdk = [34], qualifiers = "sw700dp-w1000dp-h800dp-mdpi")
    fun `tablet screen entry names the tablet`() {
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet()) {} } }
        compose.onNodeWithText("AI 操作平板").assertIsDisplayed()
        compose.onNodeWithText("AI 操作手机").assertDoesNotExist()
    }
}
