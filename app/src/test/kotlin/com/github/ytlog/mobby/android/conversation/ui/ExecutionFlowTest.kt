package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionFlowTest {
    @get:Rule val compose = createComposeRule()
    private val empty = Turn(TurnId("t"), "请检查超时", null, ExecutionPhase.SUCCEEDED)
    private val step = Step.Command("s1", "ls", "ok", "SUCCEEDED")
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T
    private fun vm(): ConversationViewModel = ConversationViewModel(
        ConversationUseCases(
            stub { when (it) { "getState" -> MutableStateFlow(ConversationState()); "anchor" -> Unit; else -> error(it) } },
            stub { error(it) },
            stub { when (it) {
                "getStatus" -> flowOf(SystemStatus())
                "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
                "agents" -> emptyList<AgentOption>()
                "gateways" -> emptyList<GatewayProfile>()
                else -> error(it)
            } },
            { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { error(it) }
        )
    )

    @Test fun `timeline owns one bottom item across thinking tools reply cancellation and completion`() {
        val thought = Step.Thinking("thought", "Fixture thought", null, order = 1)
        val shown = mutableStateOf(empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(thought)))
        val conversation = Conversation(ConversationId("fixture"), NextTurnConfig(AgentId.CLAUDE_CODE, "fixture", null, "default", "fixture"))
        val model = vm()
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            Timeline(ConversationDetail(conversation, listOf(shown.value)), model, androidx.compose.ui.Modifier,
                read = { _, _ -> }, hostActions = ConversationHostActions({}, {}), proposal = {}, onSelectPlugin = {})
        } }
        fun assertBottom() {
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodesWithTag("turn-loading").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("正在回复…").assertCountEquals(1)
            val footer = compose.onNodeWithTag("turn-loading").getBoundsInRoot()
            val flow = compose.onNodeWithTag("execution-flow").getBoundsInRoot()
            assertTrue("Loading must follow the execution flow", footer.top >= flow.bottom)
        }
        assertBottom()
        compose.runOnIdle { shown.value = shown.value.copy(steps = listOf(thought.copy(outcome = "SUCCEEDED"), step.copy(outcome = null, order = 2))) }
        assertBottom()
        compose.runOnIdle { shown.value = shown.value.copy(messages = listOf(Message("reply", "Fixture reply", order = 3))) }
        assertBottom()
        compose.runOnIdle { shown.value = shown.value.copy(phase = ExecutionPhase.CANCELLING) }
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodesWithTag("turn-loading").assertCountEquals(1)
        compose.onNodeWithText("正在停止…").assertExists()
        compose.runOnIdle { shown.value = shown.value.copy(occupied = false, phase = ExecutionPhase.CANCELLED) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("turn-loading").assertDoesNotExist()
    }

    @Test fun `execution cards never own loading markers for thinking or tools`() {
        val thought = Step.Thinking("thought", "Inspect the fixture", null, order = 1)
        val thinking = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(thought))
        val tool = thinking.copy(steps = listOf(step.copy(outcome = null, order = 2)))
        val model = vm()
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            ExecutionFlow(thinking, model, thinking.steps) { _, _ -> }
            ExecutionFlow(tool, model, tool.steps) { _, _ -> }
        } } }
        compose.onNodeWithText("Inspect the fixture").assertDoesNotExist()
        compose.onNodeWithText("运行 ls").assertExists()
        compose.onAllNodesWithContentDescription("正在回复…").assertCountEquals(0)
    }

    @Test fun `copy and share stay hidden until the turn ends`() {
        val reply = empty.copy(messages = listOf(Message("m", "hello")), occupied = true, phase = ExecutionPhase.RUNNING)
        assertFalse(reply.replyActionsVisible())
        assertFalse(reply.copy(occupied = false, pending = true).replyActionsVisible())
        assertTrue(reply.copy(occupied = false, pending = false, phase = ExecutionPhase.SUCCEEDED).replyActionsVisible())
        val phase = mutableStateOf(ExecutionPhase.RUNNING)
        compose.setContent { MaterialTheme { ReplyActivity(phase.value) } }
        compose.onNodeWithContentDescription("正在回复…").assertExists()
        compose.onNodeWithText("正在回复…").assertDoesNotExist()
        compose.runOnIdle { phase.value = ExecutionPhase.CANCELLING }
        compose.onNodeWithText("正在停止…").assertExists()
        compose.onNodeWithContentDescription("正在回复…").assertDoesNotExist()
    }

    @Test fun `bottom loading is independent of replies tools and thinking`() {
        val running = empty.copy(occupied = true, phase = ExecutionPhase.RUNNING)
        assertTrue(running.showsSeparateActivity())
        assertTrue(running.copy(messages = listOf(Message("m", "hello"))).showsSeparateActivity())
        assertTrue(running.copy(steps = listOf(Step.Thinking("thought", "private", null))).showsSeparateActivity())
        assertTrue(running.copy(steps = listOf(step.copy(outcome = null))).showsSeparateActivity())
        assertTrue(running.copy(phase = ExecutionPhase.CANCELLING).showsSeparateActivity())
        assertTrue(empty.copy(pending = true).showsSeparateActivity())
        assertFalse(empty.showsSeparateActivity())
    }

    @Test fun `turns without steps stay off the timeline`() {
        assertFalse(empty.hasVisibleExecution())
        assertFalse(empty.copy(occupied = true, phase = ExecutionPhase.RUNNING).hasVisibleExecution())
        assertTrue(empty.copy(steps = listOf(step)).hasVisibleExecution())
        assertFalse(empty.copy(diagnostics = listOf(Message("d", "log"))).hasVisibleExecution())
    }

    @Test fun `completed headline uses the step count and running stays compact`() {
        assertEquals("已完成 2 个步骤", empty.copy(steps = listOf(step, step.copy(id = "s2"))).executionHeadline())
        assertEquals("执行中", empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(step.copy(outcome = null))).executionHeadline())
        assertEquals("已思考", empty.copy(steps = listOf(Step.Thinking("t", "", "SUCCEEDED"))).executionHeadline())
        val between = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(step))
        assertEquals("执行中", between.executionHeadline())
        assertTrue(between.toolGroupExpanded(between.steps))
        assertEquals("失败 · 1 个步骤", empty.copy(phase = ExecutionPhase.FAILED, steps = listOf(step)).executionHeadline())
    }

    @Test fun `dark conversation colors come from the shared palette`() {
        val conversation = MobbyColors.Dark.Conversation
        assertEquals(Color(0xFF111111), conversation.canvas)
        assertEquals(Color(0xFFDADADA), conversation.ink)
        assertEquals(Color(0xFF979797), conversation.toolInk)
        assertEquals(Color(0xFF3A3A3A), conversation.toolBorder)
        assertEquals(Color(0xFF292929), conversation.userBubble)
        assertEquals(Color(0xFFDBDBDB), conversation.userInk)
        assertEquals(Color(0xFF292929), MobbyColors.Dark.button)
        assertEquals(Color(0xFFDADADA), MobbyColors.Dark.onButton)
        assertEquals(Color(0xFF757575), conversation.replyAction)
        assertEquals(Color(0xFFA5A5A5), conversation.readerMuted)
        val seen = arrayOfNulls<Color>(7)
        compose.setContent {
            MaterialTheme(colorScheme = MobbyDarkScheme) {
                seen[0] = conversationCanvas()
                seen[1] = conversationInk()
                seen[2] = toolCallSurface()
                seen[3] = toolCallInk()
                seen[4] = userBubbleColor()
                seen[5] = userBubbleInk()
                seen[6] = replyActionColor()
            }
        }
        compose.waitForIdle()
        assertEquals(conversation.canvas, seen[0])
        assertEquals(conversation.ink, seen[1])
        assertEquals(conversation.canvas, seen[2])
        assertEquals(conversation.toolInk, seen[3])
        assertEquals(conversation.userBubble, seen[4])
        assertEquals(conversation.userInk, seen[5])
        assertEquals(conversation.replyAction, seen[6])
    }

    @Test fun `zero step completion does not render an empty process card`() {
        compose.setContent { MaterialTheme { ExecutionFlow(empty, vm()) { _, _ -> } } }
        compose.onNodeWithText("没有工具执行步骤").assertDoesNotExist()
        compose.onNodeWithText("个步骤", substring = true).assertDoesNotExist()
        compose.onNodeWithText("本机").assertDoesNotExist()
        compose.onNodeWithContentDescription("已收起").assertDoesNotExist()
    }

    @Test fun `unfinished tools stay open and a finished run starts collapsed`() {
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(step.copy(outcome = null)))
        assertTrue(running.toolGroupExpanded(running.steps))
        assertFalse(empty.copy(steps = listOf(step)).toolGroupExpanded(listOf(step)))
        compose.setContent { MaterialTheme { ExecutionFlow(running, vm()) { _, _ -> } } }
        compose.onNodeWithText("运行 ls").assertExists()
    }

    @Test fun `a finished step card shows a compact completed header`() {
        compose.setContent { MaterialTheme { ExecutionFlow(empty.copy(steps = listOf(step), expanded = false), vm()) { _, _ -> } } }
        compose.onNodeWithText("已完成 1 个步骤").assertExists()
        compose.onNodeWithText("运行 ls").assertDoesNotExist()
        compose.onNodeWithText("本机").assertDoesNotExist()
        compose.onNodeWithText("完成 · 1 个步骤").assertDoesNotExist()
    }

    @Test fun `expanded steps list the readable tool title not the raw json`() {
        val open = empty.copy(steps = listOf(step), expandedSteps = setOf("tools:s1"))
        compose.setContent { MaterialTheme { ExecutionFlow(open, vm()) { _, _ -> } } }
        compose.onNodeWithText("已完成 1 个步骤").assertExists()
        compose.onNodeWithText("运行 ls").assertExists()
        compose.onNodeWithText("""{"command":"ls"}""").assertDoesNotExist()
    }

    @Test fun `thinking hides routine diagnostics and a failed turn still offers them`() {
        val log = listOf(Message("d", "cli stderr"))
        val thinking = Step.Thinking("think", "", null)
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(thinking), diagnostics = log)
        assertFalse(running.diagnosticsActionVisible())
        val finished = running.copy(occupied = false, phase = ExecutionPhase.SUCCEEDED, steps = listOf(thinking.copy(outcome = "SUCCEEDED")), expandedSteps = setOf("tools:think"))
        assertFalse(finished.diagnosticsActionVisible())
        val failed = finished.copy(phase = ExecutionPhase.FAILED, failure = "执行失败")
        assertTrue(failed.diagnosticsActionVisible())
        val shown = mutableStateOf(running)
        compose.setContent { MaterialTheme { ExecutionFlow(shown.value, vm()) { _, _ -> } } }
        compose.onNodeWithText("思考").assertExists()
        compose.onNodeWithText("查看诊断", substring = true).assertDoesNotExist()
        compose.runOnIdle { shown.value = failed }
        compose.onNodeWithText("查看诊断（1）").assertExists()
    }

    @Test fun `thinking stays collapsed while streaming and opens only on request`() {
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(
            step.copy(outcome = null),
            Step.Thinking("think", "private", null)))
        val shown = mutableStateOf(running)
        compose.setContent { MaterialTheme { ExecutionFlow(shown.value, vm()) { _, _ -> } } }
        compose.onNodeWithText("执行中").assertExists()
        compose.onNodeWithText("思考").assertExists()
        compose.onNodeWithText("private").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("正在回复…").assertCountEquals(0)
        compose.onNodeWithText("运行 ls").assertExists()
        val finished = running.copy(occupied = false, phase = ExecutionPhase.SUCCEEDED, steps = listOf(
            step, Step.Thinking("think", "private", "SUCCEEDED")), expandedSteps = setOf("tools:s1"))
        compose.runOnIdle { shown.value = finished }
        compose.onNodeWithText("private").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在回复…").assertDoesNotExist()
        compose.runOnIdle { shown.value = finished.copy(expandedSteps = setOf("tools:s1", "think")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("private").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("正在回复…").assertDoesNotExist()
    }
}
