package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ConversationDrawerPageTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    @After fun cleanup() { scope.cancel() }

    @Test fun `edge swipe opens the drawer only on the conversation page`() {
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
            "defaultGateway" -> null
            "capture" -> DataResult.Loaded<CameraCapture?>(null)
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.DARK)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { InteractionEntry(actions, InteractionHostActions({}, { _, _ -> }, {})) }
        compose.onNodeWithText("先配置网关").assertExists()
        compose.onNodeWithText("去配置网关").performClick()
        compose.onNodeWithText("添加网关").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("先配置网关").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("网关设置").assertExists()
        swipeFromLeftEdge()
        compose.onNodeWithText("网关设置").assertExists()
        compose.onNodeWithText("新对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("打开会话抽屉").assertExists()
        swipeFromLeftEdge()
        compose.onNodeWithText("新对话").assertExists()
    }

    private fun swipeFromLeftEdge() {
        compose.onRoot().performTouchInput {
            down(Offset(16.dp.toPx(), centerY))
            repeat(12) {
                advanceEventTime(16)
                moveBy(Offset(18.dp.toPx(), 0f))
            }
            advanceEventTime(16)
            up()
        }
        compose.waitForIdle()
    }
}
