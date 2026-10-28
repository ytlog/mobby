package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun `empty conversation offers four equal plugin cards and selects their actual refs`() {
        val selected = mutableListOf<String>()
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet()) { selected += it } } }
        compose.onNodeWithText("使用手机").assertIsDisplayed().performClick()
        compose.onNodeWithText("程序开发").assertIsDisplayed().performClick()
        compose.onNodeWithText("访问文件").assertIsDisplayed().performClick()
        compose.onNodeWithText("使用相机").assertIsDisplayed().performClick()
        val refs = listOf("plugin:device:screen", "plugin:device:programs", "plugin:device:storage", "plugin:device:camera")
        assertEquals(refs, selected)
        val bounds = refs.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        assertTrue(bounds.all { it.right - it.left == bounds.first().right - bounds.first().left })
        assertEquals(bounds[0].top, bounds[1].top)
        assertEquals(bounds[2].top, bounds[3].top)
        assertTrue(bounds[2].top > bounds[0].bottom)
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
        val refs = listOf("plugin:device:screen", "plugin:device:programs", "plugin:device:storage", "plugin:device:camera")
        val tops = refs.map { compose.onNodeWithTag(it).getBoundsInRoot().top }
        assertTrue(tops.all { it == tops.first() })
        compose.onNodeWithText("第四个").assertDoesNotExist()
        compose.onNodeWithText("Header Watch").performClick()
        compose.onNodeWithText("价格提醒").performClick()
        compose.onNodeWithText(UiStrings.allProgramsShort).performClick()
        compose.onNodeWithTag("plugin:device:camera").performClick()
        assertEquals(listOf("page:header-watch", "details:price", "all", "plugin:device:camera"), actions)
    }

    @Test @Config(sdk = [34], qualifiers = "sw700dp-w1000dp-h800dp-mdpi")
    fun `tablet screen entry names the tablet`() {
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet()) {} } }
        compose.onNodeWithText("使用平板").assertIsDisplayed()
        compose.onNodeWithText("使用手机").assertDoesNotExist()
    }
}
