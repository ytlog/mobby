package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
class ConversationQuickActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `empty conversation shortcuts select their actual plugin refs`() {
        val selected = mutableListOf<String>()
        compose.setContent { MaterialTheme { ConversationQuickActions(emptySet()) { selected += it } } }
        compose.onNodeWithText("使用手机").assertIsDisplayed().performClick()
        compose.onNodeWithText("查看照片").assertIsDisplayed().performClick()
        compose.onNodeWithText("访问文件").assertIsDisplayed().performClick()
        assertEquals(listOf("plugin:device:screen", "plugin:device:media", "plugin:device:storage"), selected)
    }
}
