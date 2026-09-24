package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import com.github.ytlog.mobby.android.interaction.ui.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class GatewayListTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()
    private val profile = GatewayProfile(AgentId.CODEX, "CODEX", 2, "https://openrouter.ai/api/v1", "openai/gpt-test", "RESPONSES", true,
        listOf(GatewayModel("openai/gpt-test", "GPT Test"), GatewayModel("other", "Other")))

    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args) } as T

    private fun page(profiles: List<GatewayProfile>, current: Conversation? = null,
        configured: (NextTurnConfig) -> Unit = {}, selectedDefault: (GatewayProfile) -> Unit = {}): ConversationViewModel {
        val state = MutableStateFlow(InteractionState(loading = false, selected = current?.let { ConversationDetail(it, emptyList()) }))
        val repository = stub<InteractionRepository> { name, args -> when {
            name == "getState" -> state
            name.startsWith("configure") -> {
                val config = args!![1] as NextTurnConfig
                configured(config)
                state.value = state.value.copy(selected = state.value.selected?.copy(conversation = current!!.copy(config = config)))
                current!!.id.value
            }
            else -> error(name)
        } }
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> profiles
            "defaultGateway" -> GatewayDefault(AgentId.CODEX, profile.id, profile.version)
            "selectDefaultGateway" -> { selectedDefault(args!![0] as GatewayProfile); OperationResult.Done }
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "id" }, scope,
            stub<PreferencePort> { name, _ -> error(name) })
        val vm = ConversationViewModel(actions).also { store.put("vm", it) }
        compose.setContent { MaterialTheme { GatewayPage(vm) {} } }
        return vm
    }

    @After fun cleanup() { compose.runOnIdle { store.clear() }; scope.cancel() }

    @Test fun `gateway page lists a configured gateway and opens its editor`() {
        page(listOf(profile))
        compose.onNodeWithText("探测模型（可先留空）").assertDoesNotExist()
        compose.onNodeWithText("OpenRouter · openai/gpt-test · 2 个模型").assertExists()
        compose.onNodeWithContentDescription("编辑网关").performClick()
        compose.onNodeWithText("编辑网关").assertExists()
        compose.onNodeWithText("探测模型（可先留空）").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("探测模型（可先留空）").assertDoesNotExist()
        compose.onNodeWithText("已配置").assertExists()
    }

    @Test fun `selecting a gateway applies it to current conversation and future conversations`() {
        val claude = GatewayProfile(AgentId.CLAUDE_CODE, "CLAUDE", 4, "https://example.com/v1", "claude-model", "MESSAGES", true)
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.CODEX, "old", "high", "workspace", "CODEX", 2))
        var configured: NextTurnConfig? = null
        var selectedDefault: GatewayProfile? = null
        val vm = page(listOf(profile, claude), current, { configured = it }, { selectedDefault = it })
        compose.waitUntil(5_000) { vm.state.value.selected != null }
        compose.onAllNodesWithText("Claude Code").onLast().performClick()
        compose.waitUntil(5_000) { configured != null && selectedDefault != null }
        Assert.assertEquals(AgentId.CLAUDE_CODE, configured?.agent)
        Assert.assertEquals("claude-model", configured?.model)
        Assert.assertEquals("workspace", configured?.workspace)
        Assert.assertEquals("CLAUDE", configured?.gatewayProfile)
        Assert.assertEquals(4L, configured?.gatewayVersion)
        Assert.assertEquals(AgentId.CLAUDE_CODE, selectedDefault?.agent)
        Assert.assertEquals("CLAUDE", selectedDefault?.id)
        compose.onNodeWithText("当前会话 · 新会话默认").assertExists()
    }

    @Test fun `same agent can choose a second independent gateway`() {
        val second = profile.copy(id = "second-gateway", endpoint = "https://api.openai.com/v1", model = "other-model", version = 1)
        val current = Conversation(ConversationId("current"), NextTurnConfig(AgentId.CODEX, profile.model, null, "workspace", profile.id, profile.version))
        var configured: NextTurnConfig? = null
        var selected: GatewayProfile? = null
        page(listOf(profile, second), current, { configured = it }, { selected = it })
        compose.onAllNodesWithText("Codex").onLast().performClick()
        compose.waitUntil(5_000) { configured?.gatewayProfile == second.id && selected?.id == second.id }
        Assert.assertEquals("other-model", configured?.model)
    }

    @Test fun `empty gateway page offers add and a failed catalog stays visible on the row`() {
        page(emptyList())
        compose.onNodeWithText("还没有配置网关").assertExists()
        compose.onNodeWithText("添加网关").performClick()
        compose.onNodeWithText("添加网关").assertExists()
        compose.onNodeWithText("探测模型（可先留空）").assertExists()
        compose.onNodeWithText("填写 Base 地址和密钥后探测。", substring = true).assertExists()
    }

    @Test fun `catalog failure is shown on the configured row`() {
        page(listOf(profile.copy(catalogError = "该网关没有模型列表接口", models = listOf(GatewayModel("openai/gpt-test", "GPT Test")))))
        compose.onNodeWithText("OpenRouter · openai/gpt-test · 模型列表未更新").assertExists()
        compose.onNodeWithText("添加网关").assertExists()
    }

    @Test fun `model menu prefers a unique display name`() {
        Assert.assertEquals("GPT Test", modelMenuLabel("openai/gpt-test", mapOf("openai/gpt-test" to "GPT Test", "other" to "Other")))
        Assert.assertEquals("a", modelMenuLabel("a", mapOf("a" to "same", "b" to "same")))
        Assert.assertEquals("plain", modelMenuLabel("plain", mapOf("plain" to "plain")))
    }
}
