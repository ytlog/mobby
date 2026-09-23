package com.github.ytlog.mobby.android.interaction.ui.gateway

import androidx.compose.material3.MaterialTheme
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

    @Test fun `form probes protocols without asking user to choose agents and saves selected models`() {
        var inspected: GatewayEdit? = null
        var saved: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { saved = it; GatewaySaveResult.Saved(discovered.models, null, discovered.supportedAgents) },
            { inspected = it; DataResult.Loaded(discovered) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("网关地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/v1", inspected!!.addresses.responses)
        compose.onNodeWithText("已确认：Codex、OpenCode").assertExists()
        compose.onNodeWithText("支持的 Agent").assertDoesNotExist()
        compose.onNodeWithText("Other").performScrollTo().performClick()
        compose.onNodeWithText("保存网关").performScrollTo().performClick()
        assertEquals(setOf("chat", "other"), saved!!.selectedModels)
        assertEquals("chat", saved!!.model)
    }

    @Test fun `no verified native protocol cannot be saved`() {
        val unavailable = discovered.copy(supportedAgents = emptySet())
        compose.setContent { MaterialTheme { GatewayForm(emptyList(), { runBlocking { it() } },
            { fail("save must remain disabled"); GatewaySaveResult.Failed("unexpected") },
            { DataResult.Loaded(unavailable) }, {}, {}, { DataResult.Failed("unused") }, editingId = "") } }
        field("网关地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
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
        field("网关地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认").onLast().performScrollTo().performClick()
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals(listOf("", "other"), inspectedModels)
        compose.onNodeWithText("保存网关").assertIsEnabled()
    }

    @Test fun `editing existing gateway keeps saved protocol addresses and probes again before saving`() {
        val profiles = listOf(
            GatewayProfile(AgentId.CODEX, "profile", 2, "https://gateway.invalid/v1", "chat", "RESPONSES", true),
            GatewayProfile(AgentId.CLAUDE_CODE, "profile", 2, "https://gateway.invalid/anthropic/v1", "chat", "MESSAGES", true),
        )
        var inspected: GatewayEdit? = null
        compose.setContent { MaterialTheme { GatewayForm(profiles, { runBlocking { it() } },
            { GatewaySaveResult.Failed("unused") }, { inspected = it; DataResult.Loaded(discovered) },
            {}, {}, { DataResult.Failed("unused") }, editingId = "profile") } }
        field("网关地址").assertTextContains("https://gateway.invalid/v1")
        field("Messages 地址（留空则使用上方地址）").assertTextContains("https://gateway.invalid/anthropic/v1")
        compose.onNodeWithText("保存网关").assertIsNotEnabled()
        compose.onNodeWithText("探测支持的 Agent 和模型").performScrollTo().performClick()
        assertEquals("https://gateway.invalid/anthropic/v1", inspected!!.addresses.messages)
    }
}
