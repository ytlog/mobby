package com.mobby.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mobby.interaction.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class GatewayFormTest {
    @get:Rule val compose = createComposeRule()
    private val profile = GatewayProfile(AgentId.CODEX, "CODEX", 1, "https://gateway.invalid/v1", "test-model", "RESPONSES", true)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    @Test fun `switch to unconfigured agent clears previous input and secret`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), {}, { OperationResult.Done }, {}, {}) } }
        field("已保存密钥，输入可替换").performScrollTo().performTextReplacement("synthetic-test-secret")
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        field("网关地址").assertTextEquals("网关地址", "")
        field("模型名称").assertTextEquals("模型名称", "")
        field("API Key（无鉴权可留空）").assertTextEquals("API Key（无鉴权可留空）", "")
    }
    @Test fun `saving freezes all fields and reports result for the submitted agent only`() {
        var pending: (suspend () -> Unit)? = null
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { pending = it }, { saved = it; OperationResult.Done }, {}, {}) } }
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        field("网关地址").assertIsNotEnabled()
        field("模型名称").assertIsNotEnabled()
        field("已保存密钥，输入可替换").assertIsNotEnabled()
        compose.onNodeWithText("Messages").assertIsNotEnabled()
        compose.onNodeWithText("移除已保存密钥").assertIsNotEnabled()
        compose.onNodeWithText("Claude Code").assertIsNotEnabled()
        compose.runOnIdle { runBlocking { pending!!() } }
        assertEquals(AgentId.CODEX, saved!!.agent)
        compose.onNodeWithText("配置已保存，尚未测试连接").assertExists()
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("配置已保存，尚未测试连接").assertDoesNotExist()
    }
    @Test fun `failed save retains edits for retry and clears transient credential buffer`() {
        var pending: (suspend () -> Unit)? = null
        var request: GatewayEdit? = null
        var sentKey: String? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { pending = it }, {
            request = it; sentKey = it.credential?.concatToString(); OperationResult.Failed("保存失败，请重试")
        }, {}, {}) } }
        field("网关地址").performScrollTo().performTextReplacement("https://changed.invalid/v1")
        field("已保存密钥，输入可替换").performScrollTo().performTextReplacement("synthetic-test-secret")
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        compose.runOnIdle { runBlocking { pending!!() } }
        assertEquals("synthetic-test-secret", sentKey)
        assertTrue(request!!.credential!!.all { it == '\u0000' })
        field("网关地址").assertTextContains("https://changed.invalid/v1").assertIsEnabled()
        field("API Key（无鉴权可留空）").assertIsEnabled()
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithText("配置已保存，尚未测试连接").assertDoesNotExist()
    }

}
