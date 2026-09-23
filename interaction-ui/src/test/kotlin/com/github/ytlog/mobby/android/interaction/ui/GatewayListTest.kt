package com.github.ytlog.mobby.android.interaction.ui

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

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    private fun page(profiles: List<GatewayProfile>) {
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> MutableStateFlow(InteractionState(loading = false))
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> profiles
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "id" }, scope,
            stub<PreferencePort> { error(it) })
        val vm = ConversationViewModel(actions).also { store.put("vm", it) }
        compose.setContent { MaterialTheme { GatewayPage(vm) {} } }
    }

    @After fun cleanup() { compose.runOnIdle { store.clear() }; scope.cancel() }

    @Test fun `gateway page lists a configured gateway and opens its editor`() {
        page(listOf(profile))
        compose.onNodeWithText("模型名称").assertDoesNotExist()
        compose.onNodeWithText("OpenRouter · openai/gpt-test · 2 个模型").assertExists()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("编辑网关").assertExists()
        compose.onNodeWithText("模型名称").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("模型名称").assertDoesNotExist()
        compose.onNodeWithText("已配置").assertExists()
    }

    @Test fun `empty gateway page offers add and a failed catalog stays visible on the row`() {
        page(emptyList())
        compose.onNodeWithText("还没有配置网关").assertExists()
        compose.onNodeWithText("添加网关").performClick()
        compose.onNodeWithText("添加网关").assertExists()
        compose.onNodeWithText("模型名称").assertExists()
        compose.onNodeWithText("Codex 使用 Responses，通过本地桥接连接网关。").assertExists()
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
