package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ConversationDrawerPageTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    @After fun cleanup() { scope.cancel() }

    @Test fun `edge swipe opens the drawer only on the conversation page`() {
        val repository = stub<ConversationStore> { name -> when (name) {
            "getState" -> MutableStateFlow(ConversationState(loading = false))
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
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { ConversationEntry(actions, ConversationHostActions({}, {})) }
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

    @Test @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun `expanded window keeps conversation list visible without drawer button`() {
        val conversation = Conversation(ConversationId("wide"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val repository = stub<ConversationStore> { name -> when (name) {
            "getState" -> MutableStateFlow(ConversationState(loading = false, selected = ConversationDetail(conversation, emptyList())))
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
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { ConversationEntry(actions, ConversationHostActions({}, {})) }
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithTag("drawer-new").assertExists()
        compose.onNodeWithContentDescription("打开会话抽屉").assertDoesNotExist()
        compose.onNodeWithTag("conversation-transcript").assertExists()
        val list = compose.onNodeWithTag("drawer-new").getUnclippedBoundsInRoot()
        val collapse = compose.onNodeWithTag("sidebar-collapse").getUnclippedBoundsInRoot()
        val detailNew = compose.onNodeWithContentDescription("新建对话").getUnclippedBoundsInRoot()
        assertTrue("sidebar controls need room for their shadows", collapse.left - list.right >= 8.dp)
        assertTrue("list and detail controls must share a center line", abs((list.top + list.bottom - detailNew.top - detailNew.bottom).value) <= 2f)
        val search = compose.onNodeWithTag("drawer-search").getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription("设置").getUnclippedBoundsInRoot()
        assertTrue("footer shadows must not overlap between search and settings", settings.left - search.right >= 14.dp)
        val mic = compose.onNodeWithTag("voice-mic").getUnclippedBoundsInRoot()
        assertTrue("bottom search must match the visible composer height", abs((search.bottom - search.top - mic.bottom + mic.top - 8.dp).value) <= 1f)
        assertTrue("bottom search and composer should share a top line", abs((search.top - mic.top + 4.dp).value) <= 1f)
        assertTrue("bottom settings and composer should share a bottom line", abs((settings.bottom - mic.bottom - 4.dp).value) <= 1f)
        val detail = compose.onNodeWithTag("conversation-transcript").getUnclippedBoundsInRoot()
        assertTrue("list must be left of detail", list.right <= detail.left)
        compose.onNodeWithContentDescription("搜索会话").performTextInput("keep-search")
        compose.onNodeWithTag("sidebar-collapse").performClick()
        compose.onNodeWithTag("drawer-new").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开会话侧栏").assertExists()
        val expandedDetail = compose.onNodeWithTag("conversation-transcript").getUnclippedBoundsInRoot()
        assertTrue("detail should use the released width", expandedDetail.left < detail.left)
        compose.onNodeWithContentDescription("展开会话侧栏").performClick()
        compose.onNodeWithTag("drawer-new").assertExists()
        compose.onNodeWithContentDescription("展开会话侧栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索会话").assertTextEquals("keep-search")
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
