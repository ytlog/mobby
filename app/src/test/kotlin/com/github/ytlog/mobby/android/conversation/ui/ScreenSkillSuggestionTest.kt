package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSkillSuggestionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recognizesScreenActionsWithoutMatchingOrdinaryQuestions() {
        listOf("我要操作屏幕", "帮我打开微信", "进入手机设置看一下蓝牙", "截图给我看看", "帮我点一下那个按钮",
            "Open the Settings app", "Take a screenshot", "Tap the Continue button").forEach {
            assertTrue(it, requestsScreenSkill(it))
        }
        listOf("屏幕分辨率是多少", "怎么操作屏幕", "不要操作屏幕", "帮我写一个截图工具", "打开项目里的文件",
            "分析这张截图", "How do I take a screenshot?", "Write a script to tap a button").forEach {
            assertFalse(it, requestsScreenSkill(it))
        }
    }

    @Test fun confirmationAddsOnceAndDismissalLeavesTheDraftAlone() {
        val text = mutableStateOf("帮我打开微信")
        val attached = mutableStateOf(false)
        var added = 0
        compose.setContent {
            MaterialTheme {
                rememberScreenSkillSuggestion("chat", text.value, !attached.value) {
                    added++
                    attached.value = true
                }
            }
        }
        val title = compose.onNodeWithText(UiStrings.suggestScreenSkillTitle)
        compose.waitUntil(5_000) { title.isDisplayed() }
        compose.onNodeWithText(UiStrings.cancel).performClick()
        compose.runOnIdle {
            assertEquals(0, added)
            text.value = "帮我打开微信看看"
        }
        title.assertDoesNotExist()
        compose.runOnIdle { text.value = "" }
        compose.waitForIdle()
        compose.runOnIdle { text.value = "截图给我看看" }
        compose.waitUntil(5_000) { title.isDisplayed() }
        compose.onNodeWithText(UiStrings.suggestScreenSkillAdd).performClick()
        compose.runOnIdle {
            assertEquals(1, added)
            assertEquals("截图给我看看", text.value)
        }
        title.assertDoesNotExist()
    }
}
