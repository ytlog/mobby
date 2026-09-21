package com.mobby.interaction.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationLinkNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T
    @After fun cleanup() { scope.cancel() }

    @Test fun `each new link opens conversation but consumed link does not replay after restoration`() {
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> MutableStateFlow(InteractionState(loading = false))
            "awaitAttachmentRecovery" -> Unit
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "capture" -> DataResult.Loaded<CameraCapture?>(null)
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.SYSTEM)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        val request = mutableStateOf<String?>(null)
        val restore = StateRestorationTester(compose)
        restore.setContent { InteractionEntry(actions, InteractionHostActions({}, { _, _ -> }, {}), request.value) }
        fun settings() {
            compose.onNodeWithContentDescription("打开会话抽屉").performClick()
            compose.onNodeWithText("设置与运行环境").performClick()
            compose.onNodeWithText("网关设置").assertExists()
        }
        settings()
        compose.runOnIdle { request.value = "first-link" }
        compose.onNodeWithContentDescription("打开会话抽屉").assertExists()
        compose.onNodeWithText("网关设置").assertDoesNotExist()
        settings()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("网关设置").assertExists()
        compose.runOnIdle { request.value = "second-link-to-same-conversation" }
        compose.onNodeWithContentDescription("打开会话抽屉").assertExists()
        compose.onNodeWithText("网关设置").assertDoesNotExist()
    }
}
