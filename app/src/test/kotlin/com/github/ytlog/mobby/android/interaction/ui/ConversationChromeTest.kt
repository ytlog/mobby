package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ConversationChromeTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val interaction = MutableStateFlow(InteractionState(loading = false, selected = detail(AgentId.CLAUDE_CODE)))

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "${T::class.java.simpleName}Stub"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> body(method.name)
            }
        } as T

    @After fun cleanup() { scope.cancel() }

    @Test fun `transcript stays between chrome, agent chip has no arrow, and latest jump is only an icon`() {
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            "capture" -> DataResult.Loaded<CameraCapture?>(null)
            else -> error(name)
        } }
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> interaction
            "anchor" -> Unit
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.SYSTEM)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        val finalParsingStarted = CountDownLatch(1)
        val releaseFinalReply = CountDownLatch(1)
        compose.setContent {
            CompositionLocalProvider(LocalReplyParser provides { source ->
                if (source.startsWith("transcript-line reply 12")) {
                    finalParsingStarted.countDown()
                    releaseFinalReply.await(30, TimeUnit.SECONDS)
                }
                ReplyMarkdown.parse(source)
            }) { InteractionEntry(actions, InteractionHostActions({}, { _, _ -> }, {})) }
        }
        compose.waitForIdle()

        assertAgentChipHasNoArrow("Claude Code")
        compose.runOnIdle {
            val current = interaction.value.selected!!
            interaction.value = interaction.value.copy(selected = current.copy(conversation = current.conversation.copy(
                config = current.conversation.config.copy(agent = AgentId.CODEX, gatewayProfile = "CODEX"),
            )))
        }
        compose.waitForIdle()
        assertAgentChipHasNoArrow("Codex")
        compose.onNodeWithText("最新消息").assertDoesNotExist()
        val jump = compose.onNodeWithContentDescription("最新消息").assertIsDisplayed().getBoundsInRoot()
        assertTranscriptClearOfChrome()
        assertTrue("latest jump overlaps the composer", jump.bottom <= composerBounds().top + 1.dp)
        assertTrue("latest jump overlaps the toolbar", jump.top + 1.dp >= toolbarBounds().bottom)

        compose.onNodeWithContentDescription("最新消息").performClick()
        compose.waitUntil(10_000) { finalParsingStarted.count == 0L }
        releaseFinalReply.countDown()
        compose.waitForIdle()
        // Parsing finishes off the main thread; wait for the final layout and follow scroll too.
        compose.waitUntil(10_000) { compose.onNodeWithText("END OF FINAL REPLY").isDisplayed() }
        compose.onNodeWithText("END OF FINAL REPLY").assertIsDisplayed()
        compose.onNodeWithContentDescription("最新消息").assertDoesNotExist()

        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("最新消息").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        compose.waitForIdle()
        assertTranscriptClearOfChrome()
    }

    private fun assertAgentChipHasNoArrow(label: String) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
        val chip = compose.onNodeWithText(label).getBoundsInRoot()
        val trailing = chip.right - text.right
        assertTrue("$label chip keeps a trailing arrow ($trailing)", trailing < 32.dp)
    }

    private fun assertTranscriptClearOfChrome() {
        val toolbar = toolbarBounds()
        val composer = composerBounds()
        val transcript = compose.onNodeWithTag("conversation-transcript").getBoundsInRoot()
        assertEquals(toolbar.bottom.value, transcript.top.value, 0.5f)
        assertEquals(transcript.bottom.value, composer.top.value, 0.5f)
        val lines = compose.onAllNodesWithText("transcript-line", substring = true)
        val count = lines.fetchSemanticsNodes().size
        var visible = 0
        repeat(count) { index ->
            val bounds = lines[index].getBoundsInRoot()
            if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return@repeat
            visible++
            assertTrue("transcript overlaps toolbar: $bounds toolbar=$toolbar", bounds.top + 1.dp >= toolbar.bottom)
            assertTrue("transcript overlaps composer: $bounds composer=$composer", bounds.bottom <= composer.top + 1.dp)
        }
        assertTrue("expected visible transcript", visible > 0)
    }

    private fun toolbarBounds() = compose.onNodeWithTag("conversation-toolbar").getBoundsInRoot()
    private fun composerBounds() = compose.onNodeWithTag("conversation-composer").getBoundsInRoot()

    private fun detail(agent: AgentId): ConversationDetail {
        val turns = (1..12).map { index ->
            Turn(
                TurnId("t$index"),
                "transcript-line $index",
                null,
                ExecutionPhase.SUCCEEDED,
                messages = listOf(Message("m$index", if (index == 12) "transcript-line reply $index\n\n" + "A long final reply.\n\n".repeat(80) + "END OF FINAL REPLY" else "transcript-line reply $index", 1)),
            )
        }
        return ConversationDetail(
            Conversation(
                ConversationId("c"),
                NextTurnConfig(agent, "model", null, "default", "fixture-gateway"),
                anchor = "user:t1",
            ),
            turns,
        )
    }
}
