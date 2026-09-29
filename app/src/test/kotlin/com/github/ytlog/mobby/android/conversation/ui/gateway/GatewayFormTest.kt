package com.github.ytlog.mobby.android.conversation.ui.gateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.DataResult
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
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
    private val discovered = GatewayCatalogResult(listOf(GatewayModel("chat", "Chat"), GatewayModel("other", "Other")), null)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test fun `new gateway reveals model controls only after fetching catalog`() {
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { DataResult.Loaded(discovered) }, {}, {},
            { DataResult.Failed("unused") }, editingId = "") } }
        compose.onNodeWithText("获取模型").assertExists()
        compose.onNodeWithText("模型：请选择").assertDoesNotExist()
        compose.onNodeWithText("保存网关").assertDoesNotExist()
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").assertExists()
        compose.onNodeWithText("保存网关").assertExists()
    }

    @Test fun `fetch button shows loading until model catalog completes`() {
        val pending = CompletableDeferred<DataResult<GatewayCatalogResult>>()
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            GatewayForm(emptyList(), { task -> scope.launch { task() } },
                { GatewaySaveResult.Failed("unused") }, { pending.await() }, {}, {},
                { DataResult.Failed("unused") }, editingId = "")
        } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithTag("gateway-fetch-loading").assertExists()
        compose.onNodeWithText("模型：请选择").assertDoesNotExist()
        compose.runOnIdle { pending.complete(DataResult.Loaded(discovered)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("模型：请选择").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gateway-fetch-loading").assertDoesNotExist()
    }

    @Test fun `changing the address requires a fresh model fetch`() {
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { DataResult.Loaded(discovered) }, {}, {},
            { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://first.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").assertExists()
        field("Base 地址").performScrollTo().performTextReplacement("https://second.invalid/v1")
        compose.onNodeWithText("模型：请选择").assertDoesNotExist()
        compose.onNodeWithText("保存网关").assertDoesNotExist()
    }

    @Test fun `save button shows loading while agents are probed and closes only after success`() {
        val pending = CompletableDeferred<GatewaySaveResult>()
        var savedNotice: String? = null
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            GatewayForm(emptyList(), { task -> scope.launch { task() } },
                { pending.await() }, { DataResult.Loaded(discovered) }, {}, {},
                { DataResult.Failed("unused") }, editingId = "", onSaved = { savedNotice = it })
        } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("模型：请选择").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        compose.onNodeWithTag("gateway-save-loading").assertExists()
        assertNull(savedNotice)
        compose.runOnIdle { pending.complete(GatewaySaveResult.Saved(discovered.models, null, setOf(AgentId.CODEX))) }
        compose.waitUntil(5_000) { savedNotice != null }
        compose.onNodeWithTag("gateway-save-loading").assertDoesNotExist()
    }

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
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.responses)
    }

    @Test fun `form fetches models then saves selected models without a separate agent probe`() {
        var fetched: GatewayEdit? = null
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saved = it; GatewaySaveResult.Saved(discovered.models, null, setOf(AgentId.CODEX, AgentId.OPEN_CODE)) },
            { fetched = it; DataResult.Loaded(discovered) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1/responses")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", fetched!!.addresses.responses)
        assertEquals("https://gateway.invalid/v1", fetched!!.addresses.messages)
        compose.onNodeWithText("Responses", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Messages", substring = true).assertDoesNotExist()
        compose.onNodeWithText("支持的 Agent").assertDoesNotExist()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("模型：chat").performScrollTo().performClick()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(setOf("chat", "other"), saved!!.selectedModels)
        assertEquals("chat", saved!!.model)
    }

    @Test fun `manual model ids can be saved when the catalog is unavailable`() {
        var saved: GatewayEdit? = null
        val noCatalog = GatewayCatalogResult(emptyList(), "没有模型列表接口")
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saved = it; GatewaySaveResult.Saved(emptyList(), null, setOf(AgentId.CODEX)) },
            { DataResult.Loaded(noCatalog) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onNodeWithText("添加模型").performClick()
        field("模型 ID").performTextReplacement("first")
        compose.onNodeWithText("添加", useUnmergedTree = true).performClick()
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

    @Test fun `failed automatic agent probe leaves the gateway unsaved`() {
        var saveCalls = 0
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saveCalls++; GatewaySaveResult.Failed("未确认任何可用 Agent") },
            { DataResult.Loaded(discovered) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(1, saveCalls)
        compose.onNodeWithText("未确认任何可用 Agent").assertExists()
    }

    @Test fun `changing default model does not fetch catalog again before saving`() {
        var fetches = 0
        var savedModel: String? = null
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { savedModel = it.model; GatewaySaveResult.Saved(discovered.models, null, setOf(AgentId.CODEX)) },
            { fetches++; DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("Base 地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[1].performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(1, fetches)
        assertEquals("other", savedModel)
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
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        field("搜索模型").performTextReplacement("model-446")
        compose.onNodeWithText("Model 446").assertExists()
    }

    @Test fun `editing existing gateway shows saved agents and fetches models before saving`() {
        val profiles = listOf(
            GatewayProfile(AgentId.CODEX, "profile", 2, "https://gateway.invalid/v1", "chat", "RESPONSES", true),
            GatewayProfile(AgentId.CLAUDE_CODE, "profile", 2, "https://gateway.invalid/v1", "chat", "MESSAGES", true),
        )
        var fetched: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(profiles, { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { fetched = it; DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "profile") } }
        field("Base 地址").assertTextContains("https://gateway.invalid/v1")
        compose.onNodeWithText("已保存的 Agent：Codex、Claude Code").assertExists()
        compose.onNodeWithText("Messages 地址（留空则使用上方地址）").assertDoesNotExist()
        compose.onNodeWithText("保存网关").assertDoesNotExist()
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", fetched!!.addresses.messages)
        compose.onNodeWithText("保存网关").assertIsEnabled()
    }
}
