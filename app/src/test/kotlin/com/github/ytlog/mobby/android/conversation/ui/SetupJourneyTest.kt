package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class SetupJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var accounts = emptyList<AgentLogin>()
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T
    @After fun close() { scope.cancel() }
    private fun show(api: Boolean = false) {
        val store = stub<ConversationStore> { when (it) {
            "getState" -> MutableStateFlow(ConversationState(loading = false))
            else -> Unit
        } }
        val system = stub<SystemPort> { when (it) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "nativeAccounts" -> accounts
            "connectionSettings" -> emptyList<AgentConnectionPreference>()
            "agents" -> emptyList<AgentOption>()
            "gateways" -> if (api) listOf(GatewayProfile(AgentId.CODEX, "fixture-api", 1, "https://example.invalid/v1", "model", "RESPONSES", true)) else emptyList<GatewayProfile>()
            "defaultGateway" -> null
            "selectDefaultGateway" -> OperationResult.Done
            "nativeLoginMethods" -> DataResult.Loaded(listOf(AgentLoginMethod("browser", "ChatGPT"), AgentLoginMethod("device", "Device code")))
            "loginAgent" -> { accounts = listOf(AgentLogin(AgentId.CODEX, "openai", "ChatGPT", 1, listOf(GatewayModel("model", "Model")))); OperationResult.Done }
            else -> error(it)
        } }
        val preferences = stub<PreferencePort> { when (it) { "getAppearance" -> MutableStateFlow(Appearance.LIGHT); else -> error(it) } }
        val actions = ConversationUseCases(store, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { ConversationEntry(actions, ConversationHostActions({}, {})) }
    }
    @Test fun `first entry recommends native sign in even when an API already exists`() {
        show(api = true)
        compose.onNodeWithText("先登录你的 Agent").assertIsDisplayed()
        val login = compose.onNodeWithText("Codex").fetchSemanticsNode().boundsInRoot
        val api = compose.onNodeWithText("配置 API 网关").performScrollTo().fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(login.top < api.top)
        compose.onNodeWithText("下一步：屏幕操作").performScrollTo().assertIsDisplayed()
    }
    @Test fun `native login success continues to screen guidance`() {
        show()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("ChatGPT").performClick()
        compose.onNodeWithText("再配置屏幕操作").assertIsDisplayed()
        compose.onNodeWithText("配置屏幕操作模式").assertIsDisplayed()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0)
        compose.runOnIdle { Assert.assertEquals("SCREEN", prefs.getString("step", null)) }
    }
    @Test fun `cancelling login returns to agent guidance without advancing`() {
        show()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("先登录你的 Agent").assertIsDisplayed()
        compose.onNodeWithText("再配置屏幕操作").assertDoesNotExist()
    }
    @Test fun `finishing guidance does not change existing connection configuration`() {
        show(api = true)
        compose.onNodeWithText("下一步：屏幕操作").performScrollTo().performClick()
        compose.onNodeWithText("开始使用").performScrollTo().performClick()
        compose.onNodeWithContentDescription("打开会话抽屉").assertExists()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0)
        compose.runOnIdle { Assert.assertTrue(prefs.getBoolean("seen", false)); Assert.assertTrue(accounts.isEmpty()) }
    }
    @Test fun `back from screen guidance returns to the agent step instead of completing setup`() {
        show()
        compose.onNodeWithText("下一步：屏幕操作").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("先登录你的 Agent").assertIsDisplayed()
        compose.runOnIdle { Assert.assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0).getBoolean("seen", false)) }
    }
}
