package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.github.ytlog.mobby.android.interaction.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventHistoryPageTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `invalid and unsaved edits survive recreation without persisting`() {
        val saved = mutableListOf<EventHistoryLimits>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { EventHistoryPage({ DataResult.Loaded(EventHistoryLimits()) }, { saved += it; OperationResult.Done }, {}) } }
        compose.onNodeWithText("保留天数（1–3650）").performTextReplacement("")
        compose.onNodeWithText("保存存储设置").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存存储设置").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("保留天数（1–3650）").performTextReplacement("7")
        compose.onNodeWithText("事件内容上限（MiB，1–1024）").performTextReplacement("8")
        compose.onNodeWithText("输出保留天数（1–3650）").performScrollTo().performTextReplacement("14")
        compose.onNodeWithText("原始输出上限（MiB，1–4096）").performScrollTo().performTextReplacement("0")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存存储设置").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("原始输出上限（MiB，1–4096）").performScrollTo().performTextReplacement("64")
        compose.onNodeWithText("附件缓存上限（MiB，1–8192）").performScrollTo().performTextReplacement("0")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存存储设置").assertIsNotEnabled()
        compose.onNodeWithText("附件缓存上限（MiB，1–8192）").performScrollTo().performTextReplacement("128")
        compose.onNodeWithText("保存存储设置").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("已保存；日志与输出在后续维护时清理，附件缓存上限在下次新内容写入时生效").assertExists()
        assertEquals(listOf(EventHistoryLimits(7, 8, 14, 64, 128)), saved)
    }
    @Test fun `load and save failures remain visible and never report success`() {
        var loads = 0
        compose.setContent { MaterialTheme { EventHistoryPage({ if (loads++ == 0) DataResult.Failed("读取失败") else DataResult.Loaded(EventHistoryLimits()) }, { OperationResult.Failed("保存失败") }, {}) } }
        compose.onNodeWithText("读取失败").assertExists()
        compose.onNodeWithText("保存存储设置").assertIsNotEnabled()
        compose.onNodeWithText("重试读取").performScrollTo().performClick()
        compose.onNodeWithText("保存存储设置").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("保存失败").assertExists()
        compose.onNodeWithText("已保存；日志与输出在后续维护时清理，附件缓存上限在下次新内容写入时生效").assertDoesNotExist()
    }
}
