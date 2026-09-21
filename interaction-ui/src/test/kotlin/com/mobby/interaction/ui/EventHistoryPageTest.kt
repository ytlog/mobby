package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.mobby.interaction.domain.*
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
        compose.onNodeWithText("保存日志设置").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("保存日志设置").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("保留天数（1–3650）").performTextReplacement("7")
        compose.onNodeWithText("事件内容上限（MiB，1–1024）").performTextReplacement("8")
        compose.onNodeWithText("保存日志设置").performClick()
        compose.onNodeWithText("已保存，将在下次任务结束或启动时清理").assertExists()
        assertEquals(listOf(EventHistoryLimits(7, 8)), saved)
    }
    @Test fun `load and save failures remain visible and never report success`() {
        var loads = 0
        compose.setContent { MaterialTheme { EventHistoryPage({ if (loads++ == 0) DataResult.Failed("读取失败") else DataResult.Loaded(EventHistoryLimits()) }, { OperationResult.Failed("保存失败") }, {}) } }
        compose.onNodeWithText("读取失败").assertExists()
        compose.onNodeWithText("保存日志设置").assertIsNotEnabled()
        compose.onNodeWithText("重试读取").performClick()
        compose.onNodeWithText("保存日志设置").performClick()
        compose.onNodeWithText("保存失败").assertExists()
        compose.onNodeWithText("已保存，将在下次任务结束或启动时清理").assertDoesNotExist()
    }
}
