package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class AgentConnectionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = ViewModelStore()
    private val account = AgentLogin(AgentId.CODEX, "openai", "ChatGPT", 1, listOf(GatewayModel("account-model", "Account model")))
    private var preference = AgentConnectionPreference(AgentId.CODEX)
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args.orEmpty()) } as T
    private fun vm(api: List<GatewayProfile> = emptyList()): ConversationViewModel {
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "nativeAccounts" -> listOf(account)
            "connectionSettings" -> listOf(preference)
            "gateways" -> api
            "defaultGateway" -> GatewayDefault(AgentId.CODEX, account.route().id, account.version)
            "agents" -> listOf(AgentOption(AgentId.CODEX, mapOf("account-model" to emptySet()), null, true, emptySet()))
            "saveConnectionSetting" -> { preference = args[0] as AgentConnectionPreference; OperationResult.Done }
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, _ -> when (name) {
            "getState" -> MutableStateFlow(ConversationState(loading = false))
            else -> error(name)
        } }
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "fixture" }, scope,
            stub<PreferencePort> { name, _ -> when (name) { "getAppearance" -> MutableStateFlow(Appearance.DARK); else -> error(name) } })
        return ConversationViewModel(actions).also { owner.put("vm", it) }
    }
    @After fun cleanup() { compose.runOnIdle { owner.clear() }; scope.cancel() }

    @Test fun `only a natively signed in agent appears in the new conversation picker without any gateway`() {
        val vm = vm()
        compose.setContent { MaterialTheme { ConfigDialog(vm, null, {}, { _, _ -> }) } }
        compose.waitUntil { vm.connections.value.size == 1 }
        compose.onNodeWithText("Codex").assertExists()
        compose.onNodeWithText("Pi").assertDoesNotExist()
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.onNodeWithText("OpenCode").assertDoesNotExist()
        compose.onNodeWithText("创建").assertIsEnabled()
        compose.onNodeWithText("管理登录与 API").assertExists()
    }
    @Test fun `API-only setting keeps native login and switches the effective connection`() {
        val api = GatewayProfile(AgentId.CODEX, "existing-api", 3, "https://example.invalid/v1", "api-model", "RESPONSES", true)
        val vm = vm(listOf(api))
        compose.setContent { MaterialTheme { AgentConnectionsPage(vm, back = {}, gateway = {}) } }
        compose.waitUntil { vm.connections.value.firstOrNull()?.source == ConnectionSource.NATIVE }
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("仅使用 API 网关").performScrollTo().performClick()
        compose.waitUntil { vm.connections.value.firstOrNull()?.source == ConnectionSource.API }
        assertEquals(ConnectionMode.API_ONLY, preference.mode)
        assertEquals(listOf(account), vm.nativeAccounts.value)
        assertEquals("existing-api", vm.connections.value.single().id)
        compose.onNodeWithText("执行失败不会自动切换来源重试。使用方式的更改从下一轮生效。").performScrollTo().assertExists()
    }
}
