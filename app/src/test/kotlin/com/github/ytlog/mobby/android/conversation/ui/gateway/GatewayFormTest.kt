package com.github.ytlog.mobby.android.conversation.ui.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.DataResult
import com.github.ytlog.mobby.android.conversation.domain.OperationResult
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** On the phone, connecting meant a service menu, a base address, fetching, ticking models, a default and a separate save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class GatewayFormTest {
    @get:Rule val compose = createComposeRule()
    private val discovered = GatewayCatalogResult(listOf(GatewayModel("chat", "Chat"), GatewayModel("other", "Other")), null)
    private val connected = GatewaySaveResult.Saved(discovered.models, null, setOf(AgentId.CODEX, AgentId.OPEN_CODE))
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun connect() = compose.onNodeWithText("连接").performScrollTo().performClick()

    private fun form(profiles: List<GatewayProfile> = emptyList(),
        fetch: suspend (GatewayEdit) -> DataResult<GatewayCatalogResult> = { DataResult.Loaded(discovered) },
        save: suspend (GatewayEdit) -> GatewaySaveResult = { connected },
        delete: suspend (String) -> OperationResult = { OperationResult.Done },
        onSaved: (String) -> Unit = {}, async: Boolean = false) {
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            GatewayForm(profiles, { task -> if (async) scope.launch { task() } else runBlocking { task() } }, save, fetch, {}, {},
                { DataResult.Failed("unused") }, editingId = profiles.firstOrNull()?.id ?: "", delete = delete, onSaved = onSaved)
        } }
    }

    @Test fun `a new setup asks for a service before anything else`() {
        form()
        compose.onNodeWithText("添加 AI 服务").assertExists()
        field("API Key").assertDoesNotExist()
        compose.onNodeWithText("连接").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").performScrollTo().performClick()
        field("API Key").assertExists()
        compose.onNodeWithText("还没有 Key？去官网领取").assertExists()
        compose.onNodeWithText("连接").assertExists()
        compose.onNodeWithText("本地模型服务").assertDoesNotExist()
    }

    @Test fun `one tap fetches models, picks the recommended one and keeps every model`() {
        val catalog = GatewayCatalogResult(listOf(GatewayModel("deepseek-reasoner", "Reasoner"), GatewayModel("deepseek-chat", "Chat")), null)
        var fetched: GatewayEdit? = null
        var saved: GatewayEdit? = null
        var notice: String? = null
        form(fetch = { fetched = it; DataResult.Loaded(catalog) }, save = { saved = it; connected }, onSaved = { notice = it })
        compose.onNodeWithText("DeepSeek").performScrollTo().performClick()
        field("API Key").performTextReplacement("sk-synthetic")
        connect()
        assertEquals("https://api.deepseek.com", fetched!!.addresses.responses)
        assertEquals("deepseek-chat", saved!!.model)
        assertEquals(setOf("deepseek-reasoner", "deepseek-chat"), saved!!.selectedModels)
        assertEquals("已连接，可用于 Codex、OpenCode", notice)
    }

    @Test fun `a recommendation the service does not offer requires choosing a default`() {
        var saved: GatewayEdit? = null
        form(save = { saved = it; connected })
        compose.onNodeWithText("DeepSeek").performScrollTo().performClick()
        field("API Key").performTextReplacement("sk-synthetic")
        connect()
        assertNull(saved)
        compose.onNodeWithText("连接").assertIsNotEnabled()
        compose.onNodeWithContentDescription("设为默认 other").performScrollTo().performClick()
        connect()
        assertEquals("other", saved!!.model)
        assertEquals(setOf("other"), saved!!.selectedModels)
    }

    @Test fun `another service asks for its address and connects straight away with a single model`() {
        var saved: GatewayEdit? = null
        form(fetch = { DataResult.Loaded(GatewayCatalogResult(listOf(GatewayModel("only", "Only")), null)) }, save = { saved = it; connected })
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1/responses")
        connect()
        assertEquals("https://gateway.invalid/v1", saved!!.addresses.responses)
        assertEquals("https://gateway.invalid/v1", saved!!.addresses.messages)
        assertEquals("only", saved!!.model)
    }

    @Test fun `model configuration saves only checked models and probes the chosen default`() {
        val available = discovered.copy(models = discovered.models + GatewayModel("third", "Third"))
        var saved: GatewayEdit? = null
        form(fetch = { DataResult.Loaded(available) }, save = { saved = it; connected })
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("配置模型").performScrollTo().performClick()
        compose.onNodeWithText("Chat · chat").performScrollTo().performClick()
        compose.onNodeWithText("Third · third").performScrollTo().performClick()
        compose.onNodeWithContentDescription("设为默认 third").performScrollTo().performClick()
        assertNull(saved)
        connect()
        assertEquals("third", saved!!.model)
        assertEquals(setOf("chat", "third"), saved!!.selectedModels)
    }

    @Test fun `without a model list the user enters a model id and connects`() {
        var saved: GatewayEdit? = null
        form(fetch = { DataResult.Loaded(GatewayCatalogResult(emptyList(), "没有模型列表接口")) }, save = { saved = it; connected })
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("配置模型").performScrollTo().performClick()
        assertNull(saved)
        compose.onNodeWithText("这个服务没有提供模型列表（没有模型列表接口）。请填写模型 ID 后再连接。").assertExists()
        field("模型 ID").performScrollTo().performTextReplacement("manual")
        connect()
        assertEquals("manual", saved!!.model)
        assertEquals(setOf("manual"), saved!!.selectedModels)
    }

    @Test fun `a failed fetch or probe says why and saves nothing`() {
        var saves = 0
        var notice: String? = null
        var fetchFails = true
        form(fetch = { if (fetchFails) DataResult.Failed("模型列表认证失败（HTTP 401），请检查 Key") else DataResult.Loaded(discovered) },
            save = { saves++; GatewaySaveResult.Failed("没有连上") }, onSaved = { notice = it })
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1")
        connect()
        compose.onNodeWithText("模型列表认证失败（HTTP 401），请检查 Key").assertExists()
        assertEquals(0, saves)
        fetchFails = false
        connect()
        compose.onNodeWithContentDescription("设为默认 chat").performScrollTo().performClick()
        connect()
        assertEquals(1, saves)
        compose.onNodeWithText("没有连上").assertExists()
        assertNull(notice)
    }

    @Test fun `connect shows progress until the probe and save finish`() {
        val pending = CompletableDeferred<GatewaySaveResult>()
        var notice: String? = null
        form(fetch = { DataResult.Loaded(GatewayCatalogResult(listOf(GatewayModel("only", "Only")), null)) },
            save = { pending.await() }, onSaved = { notice = it }, async = true)
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1")
        connect()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在连接…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("gateway-connect-loading").assertExists()
        assertNull(notice)
        compose.runOnIdle { pending.complete(connected) }
        compose.waitUntil(5_000) { notice != null }
    }

    @Test fun `paste fills the key from the clipboard only when tapped`() {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("key", " sk-from-clipboard \n"))
        var fetchedKey: String? = null
        form(fetch = { fetchedKey = it.credential?.concatToString(); DataResult.Loaded(discovered) })
        compose.onNodeWithText("DeepSeek").performScrollTo().performClick()
        connect()
        assertEquals("", fetchedKey)
        compose.onNodeWithText("粘贴").performScrollTo().performClick()
        connect()
        assertEquals("sk-from-clipboard", fetchedKey)
    }

    @Test fun `editing keeps the saved key and model until the service changes`() {
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://openrouter.ai/api/v1", "chat", "RESPONSES", true, discovered.models.take(1))
        var fetched: GatewayEdit? = null
        var saved: GatewayEdit? = null
        form(listOf(profile), fetch = { fetched = it; DataResult.Loaded(discovered) }, save = { saved = it; connected })
        compose.onNodeWithText("编辑 AI 服务").assertExists()
        connect()
        assertNull(fetched!!.credential)
        assertEquals("chat", saved!!.model)
        assertEquals(setOf("chat"), saved!!.selectedModels)
        saved = null
        compose.onNodeWithText("Groq").performScrollTo().performClick()
        connect()
        assertEquals(0, fetched!!.credential!!.size)
    }

    @Test fun `editing a default keeps other checked models and applies on connect`() {
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://gateway.invalid/v1", "chat", "RESPONSES", true, discovered.models)
        var saved: GatewayEdit? = null
        form(listOf(profile), save = { saved = it; connected })
        compose.onNodeWithText("配置模型").performScrollTo().performClick()
        compose.onNodeWithContentDescription("设为默认 other").performScrollTo().performClick()
        assertNull(saved)
        connect()
        assertEquals("other", saved!!.model)
        assertEquals(setOf("chat", "other"), saved!!.selectedModels)
    }

    @Test fun `site choice, guide and delete wait under advanced settings`() {
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://api.moonshot.ai/v1", "kept", "RESPONSES", true)
        var deleted: String? = null
        form(listOf(profile), delete = { deleted = it; OperationResult.Done })
        compose.onNodeWithText("国内站").assertDoesNotExist()
        compose.onNodeWithText("删除 AI 服务").assertDoesNotExist()
        compose.onNodeWithText("高级设置").performScrollTo().performClick()
        compose.onNodeWithText("国际站").performScrollTo().assertIsSelected()
        compose.onNodeWithText("查看官方使用说明").assertExists()
        compose.onNodeWithText("删除 AI 服务").performScrollTo().performClick()
        compose.waitUntil(5_000) { deleted != null }
        assertEquals("saved", deleted)
    }

    @Test fun `the model picker searches past the first thirty models`() {
        val large = GatewayCatalogResult((1..446).map { GatewayModel("model-$it", "Model $it") }, null)
        form(fetch = { DataResult.Loaded(large) })
        compose.onNodeWithText("其他服务（填写地址）").performScrollTo().performClick()
        field("服务地址").performTextReplacement("https://gateway.invalid/v1")
        connect()
        compose.onNodeWithText("Model 446 · model-446").assertDoesNotExist()
        field("搜索模型").performScrollTo().performTextReplacement("model-446")
        compose.onNodeWithText("Model 446 · model-446").assertExists()
    }
}
