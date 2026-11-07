package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*
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
                "nativeAccounts", "connectionSettings" -> emptyList<Any>()
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

    private fun device(id: String, order: Long, status: DeviceStatus = DeviceStatus.SUCCEEDED, result: DeviceResult? = null, display: String = "screen_control", attention: Boolean = false) = DeviceRecord(
        DeviceOperation(id, "r", 1, "screen", "read", display, status, status.name.lowercase(), DeviceSubject(), JsonObject(emptyMap()), result = result,
            requiresAttention = if (attention) DeviceAttention("p", "reason", listOf("continue")) else null), "f", order)

    // A stopped turn stamped "已停止" on every group and again on the row below, though those steps had finished.
    @Test fun `step groups report their own steps and the status row reports how the run ended`() {
        val done = step.copy(id = "done", order = 1)
        val failed = step.copy(id = "failed", outcome = "FAILED", order = 2)
        val unresolved = step.copy(id = "open", outcome = null, order = 3)
        val last = step.copy(id = "last", order = 4)
        val stopped = empty.copy(phase = ExecutionPhase.CANCELLED, steps = listOf(done, failed, unresolved, last))
        assertEquals("已完成 1 个步骤", stopped.executionHeadline(listOf(done)))
        assertEquals("失败 · 1 个步骤", stopped.executionHeadline(listOf(failed)))
        // Unresolved steps make no claim of success; the status row below says the run stopped.
        assertEquals("1 个步骤", stopped.executionHeadline(listOf(unresolved)))
        assertEquals("已完成 1 个步骤", stopped.executionHeadline(listOf(last)))
    }

    // Folding made groups long, and "失败 · 11 个步骤" read as if all eleven had failed when one had.
    @Test fun `a partly failed group says how many steps failed`() {
        val ok = step.copy(id = "ok", order = 1)
        val bad = step.copy(id = "bad", outcome = "FAILED", order = 2)
        val turn = empty.copy(phase = ExecutionPhase.CANCELLED, steps = listOf(ok, bad, step.copy(id = "z", order = 3)))
        assertEquals("11 个步骤 · 1 个失败", turn.executionHeadline(listOf(ok, bad), 11))
        assertEquals("失败 · 1 个步骤", turn.executionHeadline(listOf(bad)))
    }

    // Some rows had icons and some did not, so their text started at different places.
    @Test fun `every process row starts its text at the same left edge`() {
        val failed = step.copy(id = "f", outcome = "FAILED", order = 1)
        val done = step.copy(id = "d", order = 2)
        val stopped = empty.copy(phase = ExecutionPhase.CANCELLED, steps = listOf(failed, done))
        val model = vm()
        val operation = device("op", 3, DeviceStatus.UNCONFIRMED).operation
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            ExecutionFlow(stopped, model, listOf(failed)) { _, _ -> }
            ExecutionFlow(stopped, model, listOf(done)) { _, _ -> }
            com.github.ytlog.mobby.android.deviceoperation.ui.DeviceTaskCard(operation, com.github.ytlog.mobby.android.deviceoperation.ui.DeviceCardActions())
            TurnStatus(stopped, onCancelQueued = {}, onRestore = {})
        } } }
        val lefts = listOf("失败 · 1 个步骤", "已完成 1 个步骤", "屏幕", "已停止").map { text ->
            compose.onNode(hasText(text, substring = true), useUnmergedTree = true).getBoundsInRoot().left
        }
        assertEquals(lefts.toString(), 1, lefts.distinct().size)
    }

    private fun textStyleOf(text: String): androidx.compose.ui.text.TextStyle {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single().layoutInput.style
    }

    // An opened thought was rendered exactly like a reply: same size and color, and indented further than its row.
    @Test fun `opened thinking reads as quiet process detail rather than as a reply`() {
        val thought = Step.Thinking("think", "fixture thought", "SUCCEEDED", order = 1)
        val reply = "fixture reply"
        val turn = empty.copy(steps = listOf(thought), expandedSteps = setOf("tools:think", "think"))
        val model = vm()
        var secondary = Color.Unspecified
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme, typography = MobbyTypography) {
            secondary = toolCallInk()
            androidx.compose.foundation.layout.Column {
                ExecutionFlow(turn, model, turn.steps) { _, _ -> }
                ReplyContent(reply) { _, _ -> }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("fixture thought").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty() }
        val detail = textStyleOf("fixture thought")
        assertEquals(ProcessTextSize, detail.fontSize)
        assertEquals(secondary, detail.color)
        assertEquals(MobbyTypography.bodyLarge.fontSize, textStyleOf(reply).fontSize)
        val titleLeft = compose.onNode(hasText("思考"), useUnmergedTree = true).getBoundsInRoot().left
        assertEquals(titleLeft, compose.onNodeWithText("fixture thought", useUnmergedTree = true).getBoundsInRoot().left)
    }

    @Test fun `the headline counts folded device operations`() {
        val first = step.copy(id = "a", order = 1)
        assertEquals("已完成 3 个步骤", empty.copy(steps = listOf(first)).executionHeadline(listOf(first), 3))
    }

    // Replies were separated by stacks of three process rows, which took more of the page than the replies.
    @Test fun `finished work between replies folds into one entry`() {
        val turn = empty.copy(
            messages = listOf(Message("m1", "one", order = 1), Message("m2", "two", order = 5)),
            steps = listOf(step.copy(id = "a", order = 2), step.copy(id = "b", order = 4)),
            deviceOperations = listOf(device("d", 3)))
        val entries = turn.timelineEntries()
        assertEquals(3, entries.size)
        assertTrue(entries[0] is TimelineEntry.Reply)
        assertTrue(entries[2] is TimelineEntry.Reply)
        val process = entries[1] as TimelineEntry.Process
        assertEquals(listOf("a", "b"), process.steps.map { it.id })
        assertEquals(listOf("d"), process.devices.map { it.operation.operationId })
    }

    @Test fun `operations that need a look stay out of the folded summary`() {
        val photo = DeviceResult("capture_resource", EffectState.NONE, buildJsonObject { put("mediaType", "image/jpeg") }, resourceRefs = listOf("r"))
        listOf(
            device("x", 2, DeviceStatus.UNCONFIRMED), device("x", 2, DeviceStatus.FAILED), device("x", 2, DeviceStatus.PARTIAL),
            device("x", 2, DeviceStatus.CANCELLED), device("x", 2, DeviceStatus.RUNNING), device("x", 2, attention = true),
            device("x", 2, result = photo, display = "capture"),
        ).forEach { kept ->
            val entries = empty.copy(steps = listOf(step.copy(id = "a", order = 1), step.copy(id = "b", order = 3)), deviceOperations = listOf(kept)).timelineEntries()
            assertEquals(kept.operation.status.name, 3, entries.size)
            assertEquals(kept, (entries[1] as TimelineEntry.Device).record)
        }
    }

    // Saved reading positions and search hits may name any row that has since been folded into a summary.
    @Test fun `a saved position on a folded row resolves to its summary`() {
        val turn = empty.copy(steps = listOf(step.copy(id = "a", order = 1), step.copy(id = "b", order = 3)), deviceOperations = listOf(device("d", 2)))
        val entry = turn.timelineEntries().single()
        val summary = entry.itemKey(turn)
        assertEquals("tools:t:a", summary)
        assertEquals(summary, turn.timelineKeyAliases()["tools:t:b"])
        assertEquals(summary, turn.timelineKeyAliases()["device:t:d"])
    }

    @Test fun `only the group a live run is in stays open`() {
        val earlier = step.copy(id = "a", order = 1)
        val current = step.copy(id = "b", outcome = null, order = 3)
        val live = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(earlier, current))
        assertFalse(live.toolGroupExpanded(listOf(earlier)))
        assertEquals("已完成 1 个步骤", live.executionHeadline(listOf(earlier)))
        assertTrue(live.toolGroupExpanded(listOf(current)))
        assertEquals("执行中", live.executionHeadline(listOf(current)))
    }

    // A 40dp button row plus the 28dp turn gap left almost 70dp between a reply and the next message.
    @Test fun `reply actions stay compact and the next message follows closely`() {
        val first = empty.copy(id = TurnId("one"), userText = "first", messages = listOf(Message("m1", "reply one", order = 1)))
        val second = empty.copy(id = TurnId("two"), userText = "second", messages = listOf(Message("m2", "reply two", order = 1)))
        val conversation = Conversation(ConversationId("fixture"), NextTurnConfig(AgentId.CLAUDE_CODE, "fixture", null, "default", "fixture"))
        val model = vm()
        compose.setContent { MaterialTheme {
            Timeline(ConversationDetail(conversation, listOf(first, second)), model, androidx.compose.ui.Modifier,
                read = { _, _ -> }, hostActions = ConversationHostActions({}, {}), proposal = {}, onSelectPlugin = {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("reply one").fetchSemanticsNodes().isNotEmpty() }
        val copy = compose.onAllNodesWithContentDescription(UiStrings.copyReply)[0].getBoundsInRoot()
        val nextMessage = compose.onAllNodesWithTag("user-bubble")[1].getBoundsInRoot()
        assertTrue("button height ${copy.bottom - copy.top}", copy.bottom - copy.top <= 28.dp)
        assertTrue("gap ${nextMessage.top - copy.bottom}", nextMessage.top - copy.bottom <= 16.dp)
    }

    @Test fun `a touch just below a compact reply action still reaches it`() {
        val turn = empty.copy(messages = listOf(Message("m", "reply", order = 1)))
        val conversation = Conversation(ConversationId("fixture"), NextTurnConfig(AgentId.CLAUDE_CODE, "fixture", null, "default", "fixture"))
        val model = vm()
        val shared = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            Timeline(ConversationDetail(conversation, listOf(turn)), model, androidx.compose.ui.Modifier,
                read = { _, _ -> }, hostActions = ConversationHostActions({ shared += it }, {}), proposal = {}, onSelectPlugin = {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(UiStrings.shareReply).fetchSemanticsNodes().isNotEmpty() }
        val share = compose.onNodeWithContentDescription(UiStrings.shareReply).fetchSemanticsNode().boundsInRoot
        val below = 4 * compose.density.density
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(share.center.x, share.bottom + below)) }
        compose.runOnIdle { assertEquals(listOf("reply"), shared) }
    }

    @Test fun `copy and share close the turn rather than interrupting it`() {
        val stopped = empty.copy(phase = ExecutionPhase.CANCELLED,
            messages = listOf(Message("m", "reply", order = 1)),
            steps = listOf(step.copy(id = "a", order = 2)), deviceOperations = listOf(device("d", 3)))
        val conversation = Conversation(ConversationId("fixture"), NextTurnConfig(AgentId.CLAUDE_CODE, "fixture", null, "default", "fixture"))
        val model = vm()
        compose.setContent { MaterialTheme {
            Timeline(ConversationDetail(conversation, listOf(stopped)), model, androidx.compose.ui.Modifier,
                read = { _, _ -> }, hostActions = ConversationHostActions({}, {}), proposal = {}, onSelectPlugin = {})
        } }
        compose.onAllNodesWithTag("execution-flow").assertCountEquals(1)
        compose.onNodeWithText("屏幕", substring = true).assertDoesNotExist()
        val status = compose.onNodeWithTag("turn-status").getBoundsInRoot()
        val copy = compose.onNodeWithContentDescription(UiStrings.copyReply).getBoundsInRoot()
        assertTrue("copy at ${copy.top}, status ends ${status.bottom}", copy.top >= status.bottom)
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

    // The truncation notice was drawn only inside the expanded step group; a finished run starts collapsed, so it was hidden.
    @Test fun `a turn notice is shown under the turn and not inside the collapsed step group`() {
        val truncated = empty.copy(steps = listOf(step), progress = ProgressNotice.OUTPUT_TRUNCATED)
        val shown = mutableStateOf(truncated)
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column {
            ExecutionFlow(shown.value, vm()) { _, _ -> }
            shown.value.progress?.let { TurnNotice(it) }
        } } }
        compose.onAllNodesWithText("输出超过保留上限，后续正文已截断").assertCountEquals(1)
        compose.onNodeWithText("运行 ls").assertDoesNotExist()
        // Opening the steps does not repeat it.
        shown.value = truncated.copy(expandedSteps = setOf("tools:s1"))
        compose.onNodeWithText("运行 ls").assertExists()
        compose.onAllNodesWithText("输出超过保留上限，后续正文已截断").assertCountEquals(1)
    }

    @Test fun `expanded steps list the readable tool title not the raw json`() {
        val open = empty.copy(steps = listOf(step), expandedSteps = setOf("tools:s1"))
        compose.setContent { MaterialTheme { ExecutionFlow(open, vm()) { _, _ -> } } }
        compose.onNodeWithText("已完成 1 个步骤").assertExists()
        compose.onNodeWithText("运行 ls").assertExists()
        compose.onNodeWithText("""{"command":"ls"}""").assertDoesNotExist()
    }

    @Test fun `only the failed turn status offers diagnostics`() {
        val log = listOf(Message("d", "cli stderr"))
        val thinking = Step.Thinking("think", "", null)
        val running = empty.copy(phase = ExecutionPhase.RUNNING, occupied = true, steps = listOf(thinking), diagnostics = log)
        assertFalse(running.diagnosticsActionVisible())
        val finished = running.copy(occupied = false, phase = ExecutionPhase.SUCCEEDED, steps = listOf(thinking.copy(outcome = "SUCCEEDED")), expandedSteps = setOf("tools:think"))
        assertFalse(finished.diagnosticsActionVisible())
        val failed = finished.copy(phase = ExecutionPhase.FAILED, failure = "执行失败")
        assertTrue(failed.diagnosticsActionVisible())
        assertTrue(failed.copy(failure = null).reportsOutcome())
        val diagnostics = UiStrings.viewDiagnostics(1)
        val shown = mutableStateOf(running)
        val showStatus = mutableStateOf(false)
        var diagnosticsOpened = 0
        compose.setContent {
            MaterialTheme {
                if (showStatus.value) {
                    TurnStatus(
                        shown.value,
                        onCancelQueued = {},
                        onRestore = {},
                        onDiagnostics = { diagnosticsOpened++ },
                    )
                } else {
                    ExecutionFlow(shown.value, vm()) { _, _ -> }
                }
            }
        }
        compose.onNodeWithText("思考").assertExists()
        compose.onNodeWithText("查看诊断", substring = true).assertDoesNotExist()
        compose.runOnIdle { shown.value = failed }
        compose.onNodeWithText("查看诊断", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(diagnostics).assertDoesNotExist()

        compose.runOnIdle { showStatus.value = true }
        compose.onNodeWithText("查看诊断", substring = true).assertDoesNotExist()
        val diagnosticsAction = compose.onNodeWithContentDescription(diagnostics).assertIsDisplayed()
        val icon = diagnosticsAction.getBoundsInRoot()
        val restore = compose.onNodeWithText(UiStrings.restoreToInput, useUnmergedTree = true).getBoundsInRoot()
        val card = compose.onNodeWithTag("turn-status").getBoundsInRoot()
        assertTrue("Diagnostics and Restore should share a row", restore.top < icon.bottom && icon.top < restore.bottom)
        assertTrue("Diagnostics should follow Restore", restore.right <= icon.left)
        assertTrue("Diagnostics should sit at the card's trailing inset", card.right - icon.right in 8.dp..20.dp)
        diagnosticsAction.performClick()
        compose.runOnIdle {
            assertEquals(1, diagnosticsOpened)
            shown.value = finished
        }
        compose.onNodeWithContentDescription(diagnostics).assertDoesNotExist()
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
