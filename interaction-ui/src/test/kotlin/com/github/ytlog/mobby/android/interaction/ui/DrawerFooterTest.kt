package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class DrawerFooterTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    @After fun cleanup() { scope.cancel() }

    @Test fun `drawer search keeps a settings icon and history is a single title`() {
        val conversation = Conversation(
            ConversationId("weekend"),
            NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"),
            title = "周末计划",
        )
        val interaction = MutableStateFlow(InteractionState(
            loading = false,
            conversations = listOf(ConversationSummary(conversation, ExecutionPhase.SUCCEEDED)),
            selected = ConversationDetail(conversation, emptyList()),
        ))
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> interaction
            else -> Unit
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            else -> Unit
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.LIGHT)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { InteractionEntry(actions, InteractionHostActions({}, { _, _ -> }, {})) }
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        compose.onNodeWithContentDescription("设置").assertExists()
        compose.onNodeWithText("新对话").assertExists()
        compose.onNodeWithContentDescription("搜索会话").assertExists()
        compose.onNodeWithContentDescription("关闭会话抽屉").assertDoesNotExist()
        compose.onNodeWithContentDescription("浅色外观").assertDoesNotExist()
        compose.onNodeWithContentDescription("深色外观").assertDoesNotExist()
        compose.onNodeWithText("周末计划").assertExists()
        compose.onNodeWithText("Codex · 完成", substring = true).assertDoesNotExist()
        val bar = compose.onNodeWithTag("drawer-new").fetchSemanticsNode().boundsInRoot.height
        assertEquals(bar, compose.onNodeWithTag("drawer-search").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(bar, compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertTrue(compose.onNodeWithText("周末计划").fetchSemanticsNode().boundsInRoot.height < bar)
        compose.onNodeWithText("置顶").assertDoesNotExist()
        compose.onNodeWithText("项目").assertExists()
        compose.onNodeWithText("项目管理").assertExists()
        compose.onNodeWithText("历史记录").assertExists()
        compose.onNodeWithText("历史会话").assertDoesNotExist()
    }

    @Test fun `drawer sections stay left aligned in pinned project history order`() {
        val config = NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX")
        fun conversation(id: String, title: String, pinned: Boolean = false, project: String? = null) = Conversation(
            ConversationId(id), config, title = title, pinned = pinned, project = project,
        )
        val pinned = conversation("pinned", "置顶事项", pinned = true)
        val project = conversation("project", "项目事项", project = "示例项目")
        val history = conversation("history", "周末计划")
        val interaction = MutableStateFlow(InteractionState(
            loading = false,
            projects = listOf(Project("示例项目", "default"), Project("空项目", "default")),
            conversations = listOf(pinned, project, history).map { ConversationSummary(it, ExecutionPhase.SUCCEEDED) },
            selected = ConversationDetail(history, emptyList()),
        ))
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> interaction
            else -> Unit
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            else -> Unit
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.LIGHT)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        compose.setContent { InteractionEntry(actions, InteractionHostActions({}, { _, _ -> }, {})) }
        compose.onNodeWithContentDescription("打开会话抽屉").performClick()
        val headers = listOf("置顶", "项目", "历史记录")
        val entries = listOf("置顶事项", "项目管理", "示例项目", "项目事项", "空项目")
        (headers + entries).forEach { compose.onNodeWithText(it).assertExists() }
        val headerLeft = headers.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left }
        val entryLeft = entries.map { compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left }
        headerLeft.forEach { assertEquals(headerLeft.first(), it, 1f) }
        entryLeft.forEach { assertEquals(entryLeft.first(), it, 1f) }
        assertTrue(entryLeft.first() > headerLeft.first())
        val top = (headers + entries).associateWith { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top.getValue("置顶") < top.getValue("置顶事项"))
        assertTrue(top.getValue("置顶事项") < top.getValue("项目"))
        assertTrue(top.getValue("项目") < top.getValue("项目管理"))
        assertTrue(top.getValue("项目管理") < top.getValue("示例项目"))
        assertTrue(top.getValue("示例项目") < top.getValue("项目事项"))
        assertTrue(top.getValue("项目事项") < top.getValue("空项目"))
        assertTrue(top.getValue("空项目") < top.getValue("历史记录"))
        val header = compose.onNodeWithText("mobby").fetchSemanticsNode().boundsInRoot.top
        val footer = compose.onNodeWithContentDescription("搜索会话").fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("周末计划"))
        assertEquals(header, compose.onNodeWithText("mobby").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(footer, compose.onNodeWithContentDescription("搜索会话").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(entryLeft.first(), compose.onNodeWithText("周末计划", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left, 1f)
        assertTrue(compose.onNodeWithText("历史记录").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("周末计划").fetchSemanticsNode().boundsInRoot.top)
    }
}
