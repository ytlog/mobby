package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.*
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
class ExecutionCardTest {
    @get:Rule val compose = createComposeRule()
    private val empty = Turn(TurnId("t"), "请检查超时", null, ExecutionPhase.SUCCEEDED)
    private val step = Step("s1", "bash", """{"command":"ls"}""", "ok", "SUCCEEDED")
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T
    private fun vm(): ConversationViewModel = ConversationViewModel(
        InteractionUseCases(
            stub { if (it == "getState") MutableStateFlow(InteractionState()) else error(it) },
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

    @Test fun `copy and share stay hidden until the turn ends`() {
        val reply = empty.copy(messages = listOf(Message("m", "hello")), occupied = true, phase = ExecutionPhase.RUNNING)
        assertFalse(reply.replyActionsVisible())
        assertFalse(reply.copy(occupied = false, pending = true).replyActionsVisible())
        assertTrue(reply.copy(occupied = false, pending = false, phase = ExecutionPhase.SUCCEEDED).replyActionsVisible())
        val phase = mutableStateOf(ExecutionPhase.RUNNING)
        compose.setContent { MaterialTheme { ReplyActivity(phase.value) } }
        compose.onNodeWithText("正在回复…").assertExists()
        compose.runOnIdle { phase.value = ExecutionPhase.CANCELLING }
        compose.onNodeWithText("正在停止…").assertExists()
        compose.onNodeWithText("正在回复…").assertDoesNotExist()
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
        assertEquals("已思考", empty.copy(steps = listOf(Step("t", "thinking", "思考", "", "SUCCEEDED"))).executionHeadline())
        assertEquals("已完成 1 个步骤", empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(step)).executionHeadline())
        assertEquals("失败 · 1 个步骤", empty.copy(phase = ExecutionPhase.FAILED, steps = listOf(step)).executionHeadline())
    }

    @Test fun `zero step completion does not render an empty process card`() {
        compose.setContent { MaterialTheme { ExecutionCard(empty, vm()) { _, _ -> } } }
        compose.onNodeWithText("没有工具执行步骤").assertDoesNotExist()
        compose.onNodeWithText("个步骤", substring = true).assertDoesNotExist()
        compose.onNodeWithText("本机").assertDoesNotExist()
        compose.onNodeWithContentDescription("已收起").assertDoesNotExist()
    }

    @Test fun `unfinished tools stay open and a finished run starts collapsed`() {
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(step.copy(outcome = null)))
        assertTrue(running.toolGroupExpanded(running.steps))
        assertFalse(empty.copy(steps = listOf(step)).toolGroupExpanded(listOf(step)))
        compose.setContent { MaterialTheme { ExecutionCard(running, vm()) { _, _ -> } } }
        compose.onNodeWithText("运行 ls").assertExists()
    }

    @Test fun `a finished step card shows a compact completed header`() {
        compose.setContent { MaterialTheme { ExecutionCard(empty.copy(steps = listOf(step), expanded = false), vm()) { _, _ -> } } }
        compose.onNodeWithText("已完成 1 个步骤").assertExists()
        compose.onNodeWithText("运行 ls").assertDoesNotExist()
        compose.onNodeWithText("本机").assertDoesNotExist()
        compose.onNodeWithText("完成 · 1 个步骤").assertDoesNotExist()
    }

    @Test fun `expanded steps list the readable tool title not the raw json`() {
        val open = empty.copy(steps = listOf(step), expandedSteps = setOf("tools:s1"))
        compose.setContent { MaterialTheme { ExecutionCard(open, vm()) { _, _ -> } } }
        compose.onNodeWithText("已完成 1 个步骤").assertExists()
        compose.onNodeWithText("运行 ls").assertExists()
        compose.onNodeWithText("""{"command":"ls"}""").assertDoesNotExist()
    }

    @Test fun `thinking hides routine diagnostics and a failed turn still offers them`() {
        val log = listOf(Message("d", "cli stderr"))
        val thinking = Step("think", "thinking", "思考", "", null)
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(thinking), diagnostics = log)
        assertFalse(running.diagnosticsActionVisible())
        val finished = running.copy(occupied = false, phase = ExecutionPhase.SUCCEEDED, steps = listOf(thinking.copy(outcome = "SUCCEEDED")), expandedSteps = setOf("tools:think"))
        assertFalse(finished.diagnosticsActionVisible())
        val failed = finished.copy(phase = ExecutionPhase.FAILED, failure = "执行失败")
        assertTrue(failed.diagnosticsActionVisible())
        val shown = mutableStateOf(running)
        compose.setContent { MaterialTheme { ExecutionCard(shown.value, vm()) { _, _ -> } } }
        compose.onNodeWithText("思考").assertExists()
        compose.onNodeWithText("查看诊断", substring = true).assertDoesNotExist()
        compose.runOnIdle { shown.value = failed }
        compose.onNodeWithText("查看诊断（1）").assertExists()
    }

    @Test fun `thinking stays collapsed and its private text stays off the card`() {
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(
            step.copy(outcome = null),
            Step("think", "thinking", "思考", "private", null)))
        compose.setContent { MaterialTheme { ExecutionCard(running, vm()) { _, _ -> } } }
        compose.onNodeWithText("执行中").assertExists()
        compose.onNodeWithText("思考").assertExists()
        compose.onNodeWithText("private").assertDoesNotExist()
        compose.onNodeWithText("运行 ls").assertExists()
    }
}
