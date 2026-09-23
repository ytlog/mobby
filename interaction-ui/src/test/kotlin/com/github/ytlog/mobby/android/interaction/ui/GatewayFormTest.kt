package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
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
    @Test fun `checking another agent keeps the first native address editable`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, { DataResult.Failed("unused") }) } }
        field("已保存密钥，输入可替换").performScrollTo().performTextReplacement("synthetic-test-secret")
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        field("Codex 网关地址").assertTextEquals("Codex 网关地址", "https://gateway.invalid/v1")
        field("Claude Code 网关地址").assertExists()
        field("模型名称").assertTextEquals("模型名称", "test-model")
        field("API Key（无鉴权可留空）").assertExists()
    }
    @Test fun `saving freezes all fields and reports result for the submitted agent only`() {
        var pending: (suspend () -> Unit)? = null
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { pending = it }, { saved = it; GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, { DataResult.Failed("unused") }) } }
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        field("网关地址").assertIsNotEnabled()
        field("模型名称").assertIsNotEnabled()
        field("已保存密钥，输入可替换").assertIsNotEnabled()
        compose.onNodeWithText("Messages").assertDoesNotExist()
        compose.onNodeWithText("移除已保存密钥").assertIsNotEnabled()
        compose.onNodeWithText("Claude Code").assertIsNotEnabled()
        compose.runOnIdle { runBlocking { pending!!() } }
        assertEquals(setOf(AgentId.CODEX), saved!!.endpoints.keys)
        compose.onNodeWithText("配置已保存，尚未测试连接").assertExists()
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("配置已保存，尚未测试连接").assertDoesNotExist()
    }
    @Test fun `failed save retains edits for retry and clears transient credential buffer`() {
        var pending: (suspend () -> Unit)? = null
        var request: GatewayEdit? = null
        var sentKey: String? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { pending = it }, {
            request = it; sentKey = it.credential?.concatToString(); GatewaySaveResult.Failed("保存失败，请重试")
        }, {}, {}, { DataResult.Failed("unused") }) } }
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

    @Test fun `check uses saved profile freezes edits and cancellation never reports success`() {
        val response = CompletableDeferred<DataResult<GatewayCheckReport>>()
        var checked: GatewayProfile? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, {
            checked = it; response.await()
        }) } }
        compose.onNodeWithText("测试已保存连接").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(profile, checked) }
        compose.onNodeWithText("保存当前配置").assertIsNotEnabled()
        field("网关地址").assertIsNotEnabled()
        compose.onNodeWithText("取消检查").performScrollTo().performClick()
        compose.onNodeWithText("检查已取消，未判定成功").assertExists()
        response.complete(DataResult.Loaded(GatewayCheckReport(true, "协议成功")))
        compose.onNodeWithText("协议成功").assertDoesNotExist()
        compose.onNodeWithText("测试已保存连接").assertIsEnabled()
    }
    @Test fun `unsaved edits disable checks and invalidate earlier connection result`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, {
            DataResult.Loaded(GatewayCheckReport(true, "最小请求通过"))
        }) } }
        compose.onNodeWithText("测试已保存连接").performScrollTo().performClick()
        compose.onNodeWithText("最小请求通过").assertExists()
        field("模型名称").performScrollTo().performTextReplacement("changed-model")
        compose.onNodeWithText("测试已保存连接").assertIsNotEnabled()
        compose.onNodeWithText("最小请求通过").assertDoesNotExist()
        compose.onNodeWithText("请先保存当前修改，再测试连接。").assertExists()
    }

    @Test fun `saved profile with version zero remains checkable`() {
        var checked: GatewayProfile? = null
        val legacy = profile.copy(version = 0)
        compose.setContent { MaterialTheme { GatewayForm(listOf(legacy), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, {
            checked = it; DataResult.Failed("测试网络错误")
        }) } }
        compose.onNodeWithText("测试已保存连接").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(legacy, checked) }
        compose.onNodeWithText("测试网络错误").assertExists()
    }

    @Test fun `selecting a service fills its address and custom clears that preset`() {
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { runBlocking { it() } }, { saved = it; GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, { DataResult.Failed("unused") }) } }
        field("模型名称").performScrollTo().performTextReplacement("kept-model")
        compose.onNodeWithText("OpenAI").performScrollTo().performClick()
        field("网关地址").assertTextEquals("网关地址", "https://api.openai.com/v1").assertIsNotEnabled()
        field("模型名称").assertTextContains("kept-model")
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        assertEquals("https://api.openai.com/v1", saved!!.endpoints[AgentId.CODEX])
        assertEquals("kept-model", saved!!.model)
        compose.onNodeWithText("自定义").performScrollTo().performClick()
        field("网关地址").assertTextEquals("网关地址", "").assertIsEnabled()
    }

    @Test fun `saved preset address stays selected and is not rewritten until another service is chosen`() {
        val saved = profile.copy(endpoint = "https://api.openai.com/v1/")
        compose.setContent { MaterialTheme { GatewayForm(listOf(saved), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, { DataResult.Failed("unused") }) } }
        field("网关地址").assertTextEquals("网关地址", "https://api.openai.com/v1/").assertIsNotEnabled()
        compose.onNodeWithText("OpenRouter").performScrollTo().performClick()
        field("网关地址").assertTextEquals("网关地址", "https://openrouter.ai/api/v1")
    }

    @Test fun `provider limits unsupported agent checkboxes`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), {}, { GatewaySaveResult.Saved(emptyList(), null) }, {}, {}, { DataResult.Failed("unused") }) } }
        compose.onNodeWithText("OpenAI").performScrollTo().performClick()
        compose.onNodeWithText("Claude Code").assertIsNotEnabled()
        compose.onNodeWithText("DeepSeek").assertExists()
        compose.onNodeWithText("Anthropic").assertExists()
        compose.onNodeWithText("OpenRouter").assertExists()
        compose.onNodeWithText("自定义").assertExists()
    }

    @Test fun `one preset saves separate native routes for every selected agent`() {
        val requests = mutableListOf<GatewayEdit>()
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } }, { edit ->
            requests += edit
            GatewaySaveResult.Saved(emptyList(), null)
        }, {}, {}, { DataResult.Failed("unused") }) } }
        compose.onNodeWithText("DeepSeek").performScrollTo().performClick()
        field("模型名称").performScrollTo().performTextReplacement("deepseek-flash")
        field("API Key（无鉴权可留空）").performScrollTo().performTextReplacement("fake-key")
        compose.onNodeWithText("Claude Code").performScrollTo().performClick()
        compose.onNodeWithText("OpenCode").performScrollTo().performClick()
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        assertEquals(1, requests.size)
        assertEquals(mapOf(
            AgentId.CODEX to "https://api.deepseek.com",
            AgentId.CLAUDE_CODE to "https://api.deepseek.com/anthropic/v1",
            AgentId.OPEN_CODE to "https://api.deepseek.com"
        ), requests.single().endpoints)
    }

    @Test fun `saved catalog tells how many models were stored`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { runBlocking { it() } }, {
            GatewaySaveResult.Saved(listOf(GatewayModel("test-model", "Test"), GatewayModel("other", "Other")), null)
        }, {}, {}, { DataResult.Failed("unused") }) } }
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        compose.onNodeWithText("配置已保存，已拉取 2 个模型，尚未测试连接").assertExists()
    }

    @Test fun `catalog failure keeps the save and reports why the list is missing`() {
        compose.setContent { MaterialTheme { GatewayForm(listOf(profile), { runBlocking { it() } }, {
            GatewaySaveResult.Saved(listOf(GatewayModel("test-model", "Test")), "该网关没有模型列表接口")
        }, {}, {}, { DataResult.Failed("unused") }) } }
        compose.onNodeWithText("保存当前配置").performScrollTo().performClick()
        compose.onNodeWithText("配置已保存，模型列表未能拉取。该网关没有模型列表接口").assertExists()
        compose.onNodeWithText("配置已保存，尚未测试连接").assertDoesNotExist()
    }

}
