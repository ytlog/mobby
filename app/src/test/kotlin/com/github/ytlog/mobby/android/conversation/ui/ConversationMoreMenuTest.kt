package com.github.ytlog.mobby.android.conversation.ui

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ConversationMoreMenuTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Before fun completedSetup() { SetupProgress(RuntimeEnvironment.getApplication()).save(SetupStep.DONE) }
    @After fun cleanup() { scope.cancel() }

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "${T::class.java.simpleName}Stub"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> body(method.name)
            }
        } as T

    @Test fun `more conversation actions open as the same dimmed card as the new conversation popup`() {
        val conversation = Conversation(ConversationId("more"), NextTurnConfig(AgentId.PI, "model", null, "default", "fixture-gateway"))
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "nativeAccounts", "connectionSettings" -> emptyList<Any>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            "capture" -> DataResult.Loaded<CameraCapture?>(null)
            else -> error(name)
        } }
        val state = MutableStateFlow(ConversationState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        val repository = stub<ConversationStore> { name -> when (name) {
            "getState" -> state
            "anchor" -> Unit
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.SYSTEM)
            else -> error(name)
        } }
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { ConversationEntry(actions, ConversationHostActions({}, {})) }
        compose.waitForIdle()
        assertTrue("no popup should dim the page before the menu opens", dimmedWindows().isEmpty())

        compose.onNodeWithContentDescription("更多会话操作").performClick()
        compose.waitForIdle()

        val share = compose.onNodeWithText("分享").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals("menu should be as narrow as the new conversation popup", 304f, (share.right - share.left).value, 0.5f)
        val dims = dimmedWindows()
        assertEquals("the menu should dim what lies behind it", 1, dims.size)
        assertEquals(0.18f, dims.single().dimAmount, 0.001f)
    }

    @Suppress("UNCHECKED_CAST")
    private fun dimmedWindows(): List<WindowManager.LayoutParams> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val params = global.javaClass.getDeclaredField("mParams").apply { isAccessible = true }.get(global) as List<WindowManager.LayoutParams>
        return params.filter { it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 }
    }
}
