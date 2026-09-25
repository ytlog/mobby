package com.github.ytlog.mobby.android.interaction.ui.gateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.AgentId
import com.github.ytlog.mobby.android.interaction.domain.DataResult
import com.github.ytlog.mobby.android.interaction.domain.gateway.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class GatewayFormTest {
    @get:Rule val compose = createComposeRule()
    private val discovered = GatewayInspectionResult("chat", listOf(GatewayModel("chat", "Chat"), GatewayModel("other", "Other")),
        setOf(AgentId.CODEX, AgentId.OPEN_CODE), null)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test fun `local service is absent from add gateway choices`() {
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { DataResult.Failed("unused") }, {}, {},
            { DataResult.Failed("unused") }, editingId = "") } }
        compose.onNodeWithText("服务：自定义").performClick()
        compose.onNodeWithText("本地模型服务").assertDoesNotExist()
    }

    @Test fun `typing a base path one segment at a time keeps its slash`() {
        var inspected: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { inspected = it; DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/")
        field("Base 地址").performTextInput("v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.responses)
    }

    @Test fun `form probes protocols without asking user to choose agents and saves selected models`() {
        var inspected: GatewayEdit? = null
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saved = it; GatewaySaveResult.Saved(discovered.models, null, discovered.supportedAgents) },
            { inspected = it; DataResult.Loaded(discovered) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1/responses")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.responses)
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.messages)
        compose.onNodeWithText("Responses", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Messages", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已确认：Codex、OpenCode").assertExists()
        compose.onNodeWithText("支持的 Agent").assertDoesNotExist()
        compose.onNodeWithText("模型：chat").performScrollTo().performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(setOf("chat", "other"), saved!!.selectedModels)
        assertEquals("chat", saved!!.model)
    }

    @Test fun `manual model ids can be saved when the catalog is unavailable`() {
        var saved: GatewayEdit? = null
        val noCatalog = discovered.copy(model = "first", models = emptyList(), catalogError = "没有模型列表接口")
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saved = it; GatewaySaveResult.Saved(emptyList(), null, noCatalog.supportedAgents) },
            { DataResult.Loaded(noCatalog) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：first").performScrollTo().performClick()
        compose.onNodeWithText("添加模型").performClick()
        field("模型 ID").performTextReplacement("second")
        compose.onNodeWithText("添加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模型：first").performScrollTo().performClick()
        compose.onNodeWithText("添加模型").performClick()
        field("模型 ID").performTextReplacement("third")
        compose.onNodeWithText("添加", useUnmergedTree = true).performClick()
        compose.onNodeWithText("模型：first").performScrollTo().performClick()
        compose.onNodeWithText("second").assertExists()
        compose.onNodeWithText("third").assertExists()
        compose.onNodeWithText("模型：first").performScrollTo().performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(setOf("first", "second", "third"), saved!!.selectedModels)
        assertEquals("first", saved!!.model)
    }

    @Test fun `no verified native protocol cannot be saved`() {
        val unavailable = discovered.copy(supportedAgents = emptySet())
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { fail("save must remain disabled"); GatewaySaveResult.Failed("unexpected") },
            { DataResult.Loaded(unavailable) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("未确认任何可用 Agent；请检查地址、模型、密钥和额度").assertExists()
    }

    @Test fun `selecting a catalog model as default requires a new native probe`() {
        var inspectedModels = mutableListOf<String>()
        val unavailable = discovered.copy(supportedAgents = emptySet())
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") },
            { inspectedModels += it.model; DataResult.Loaded(if (it.model == "other") discovered.copy(model = "other") else unavailable) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：chat").performScrollTo().performClick()
        compose.onNodeWithText("设为默认").performClick()
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals(listOf("", "other"), inspectedModels)
        compose.onNodeWithText("保存网关").assertIsEnabled()
    }

    @Test fun `provider selector is collapsed until opened`() {
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        compose.onNodeWithText("服务：自定义").assertExists()
        compose.onNodeWithText("OpenRouter").assertDoesNotExist()
        compose.onNodeWithText("服务：自定义").performClick()
        compose.onNodeWithText("OpenRouter").performClick()
        compose.onNodeWithText("服务：OpenRouter").assertExists()
        field("探测模型（可先留空）").assertDoesNotExist()
    }

    @Test fun `model dropdown can find an item beyond the first four hundred`() {
        val largeCatalog = discovered.copy(models = (1..446).map { GatewayModel("model-$it", "Model $it") })
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { DataResult.Loaded(largeCatalog) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：chat").performScrollTo().performClick()
        field("搜索模型").performTextReplacement("model-446")
        compose.onNodeWithText("Model 446").assertExists()
    }

    @Test fun `editing existing gateway shows detected agents and probes again before saving`() {
        val profiles = listOf(
            GatewayProfile(AgentId.CODEX, "profile", 2, "https://gateway.invalid/v1", "chat", "RESPONSES", true),
            GatewayProfile(AgentId.CLAUDE_CODE, "profile", 2, "https://gateway.invalid/v1", "chat", "MESSAGES", true),
        )
        var inspected: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(profiles, { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { inspected = it; DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "profile") } }
        field("Base 地址").assertTextContains("https://gateway.invalid/v1")
        compose.onNodeWithText("已保存的 Agent：Codex、Claude Code").assertExists()
        compose.onNodeWithText("Messages 地址（留空则使用上方地址）").assertDoesNotExist()
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.messages)
    }
}
