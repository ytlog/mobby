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
    @Before fun screenController() {
        ScreenUi.controller = object : com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort {
            override val state = MutableStateFlow(com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState())
            override suspend fun select(mode: com.github.ytlog.mobby.android.runtime.api.device.ScreenMode) { state.value = state.value.copy(mode = mode) }
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun setAdbPort(port: String) = Unit
            override fun startAdbPairing() = Unit
            override fun cancelAdbPairing() = Unit
            override suspend fun refreshPreview() = Unit
        }
    }
    @After fun close() { scope.cancel(); ScreenUi.controller = null }
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun next() = button("下一步").performClick()
    private fun show(api: Boolean = false, captureFailure: Boolean = false) {
        val store = stub<ConversationStore> { when (it) {
            "getState" -> MutableStateFlow(ConversationState(loading = false))
            else -> Unit
        } }
        val system = stub<SystemPort> { when (it) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "capture" -> if (captureFailure) DataResult.Failed("fixture camera status unavailable") else DataResult.Loaded<CameraCapture?>(null)
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
    @Test fun `choosing an agent does not mix in sign in or screen configuration`() {
        show(api = true)
        compose.onNodeWithText("选择一个 Agent").assertIsDisplayed()
        compose.onNodeWithText("Claude Code").performClick().assertIsSelected()
        compose.onNodeWithText("ChatGPT").assertDoesNotExist()
        compose.onNodeWithText("配置 API 网关").assertDoesNotExist()
        compose.onNodeWithText("选择屏幕操作方式").assertDoesNotExist()
        next()
        button("登录 Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertDoesNotExist()
    }
    @Test fun `native login success continues directly to the screen mode task`() {
        show()
        next()
        button("登录 Codex").performClick()
        compose.onNodeWithText("ChatGPT").performClick()
        compose.onNodeWithText("选择屏幕操作方式").assertIsDisplayed()
        compose.onNodeWithText("第 3 步").assertIsDisplayed()
        compose.onNodeWithText("开启开发者模式").assertDoesNotExist()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0)
        compose.runOnIdle { Assert.assertEquals("SCREEN", prefs.getString("step", null)) }
    }
    @Test fun `cancelling login stays on the sign in task without advancing`() {
        show()
        next()
        button("登录 Codex").performClick()
        compose.onNodeWithText("取消").performClick()
        button("登录 Codex").assertIsDisplayed()
        compose.onNodeWithText("选择一个 Agent").assertDoesNotExist()
        compose.onNodeWithText("选择屏幕操作方式").assertDoesNotExist()
    }
    @Test fun `previous restores the chosen agent without changing an account`() {
        show()
        compose.onNodeWithText("Claude Code").performClick()
        next()
        button("上一步").performClick()
        compose.onNodeWithText("Claude Code").assertIsSelected()
        compose.runOnIdle { Assert.assertEquals(AgentId.CLAUDE_CODE, SetupProgress(RuntimeEnvironment.getApplication()).agent()); Assert.assertTrue(accounts.isEmpty()) }
    }
    @Test fun `existing API can be reused and screen setup can be skipped without changing connections`() {
        show(api = true)
        compose.onNodeWithText("使用 API 网关").performClick()
        next()
        button("下一步").assertIsDisplayed().performClick()
        compose.onNodeWithText("暂不配置屏幕，开始使用").performClick()
        compose.onNodeWithContentDescription("打开会话抽屉").assertExists()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0)
        compose.runOnIdle { Assert.assertTrue(prefs.getBoolean("seen", false)); Assert.assertTrue(accounts.isEmpty()) }
    }
    @Test fun `back from screen mode returns to sign in instead of completing setup`() {
        show()
        next()
        compose.onNodeWithText("暂不登录，继续设置").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        button("登录 Codex").assertIsDisplayed()
        compose.onNodeWithText("虚拟屏幕（内置 ADB）").assertDoesNotExist()
        compose.runOnIdle { Assert.assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("mobby.setup-guide", 0).getBoolean("seen", false)) }
    }
    @Test fun `a background error message does not cover the pinned next button`() {
        show(captureFailure = true)
        compose.onNodeWithText("fixture camera status unavailable").assertIsDisplayed()
        next()
        button("登录 Codex").assertIsDisplayed()
    }
}
