package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.*
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
    @org.junit.Before fun completedSetup() { SetupProgress(org.robolectric.RuntimeEnvironment.getApplication()).save(SetupStep.DONE) }
    private val conversations = MutableStateFlow(ConversationState(loading = false, selected = detail(AgentId.CLAUDE_CODE)))

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

    @Test fun `transcript supplies glass backdrop while latest jump and final reply stay above composer`() {
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
        val repository = stub<ConversationStore> { name -> when (name) {
            "getState" -> conversations
            "anchor" -> Unit
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.SYSTEM)
            else -> error(name)
        } }
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        val finalParsingStarted = CountDownLatch(1)
        val releaseFinalReply = CountDownLatch(1)
        compose.setContent {
            CompositionLocalProvider(LocalReplyParser provides { source ->
                if (source.startsWith("transcript-line reply 12")) {
                    finalParsingStarted.countDown()
                    releaseFinalReply.await(30, TimeUnit.SECONDS)
                }
                ReplyMarkdown.parse(source)
            }) { ConversationEntry(actions, ConversationHostActions({}, {})) }
        }
        compose.waitForIdle()

        assertAgentChipHasNoArrow("Claude Code")
        compose.runOnIdle {
            val current = conversations.value.selected!!
            conversations.value = conversations.value.copy(selected = current.copy(conversation = current.conversation.copy(
                config = current.conversation.config.copy(agent = AgentId.CODEX, gatewayProfile = "CODEX"),
            )))
        }
        compose.waitForIdle()
        assertAgentChipHasNoArrow("Codex")
        compose.onNodeWithText("最新消息").assertDoesNotExist()
        val jump = compose.onNodeWithContentDescription("最新消息").assertIsDisplayed().getBoundsInRoot()
        assertTranscriptBehindGlass()
        assertTrue("latest jump overlaps the composer", jump.bottom <= composerBounds().top + 1.dp)
        assertTrue("latest jump overlaps the toolbar", jump.top + 1.dp >= toolbarBounds().bottom)

        compose.onNodeWithContentDescription("最新消息").performClick()
        compose.waitUntil(10_000) { finalParsingStarted.count == 0L }
        releaseFinalReply.countDown()
        compose.waitForIdle()
        // Parsing finishes off the main thread; wait for the final layout and follow scroll too.
        compose.waitUntil(10_000) { compose.onNodeWithText("END OF FINAL REPLY").isDisplayed() }
        compose.onNodeWithText("END OF FINAL REPLY").assertIsDisplayed()
        val finalReply = compose.onNodeWithText("END OF FINAL REPLY").getBoundsInRoot()
        assertTrue("final reply hidden behind glass composer", finalReply.bottom <= composerBounds().top + 1.dp)
        compose.onNodeWithContentDescription("最新消息").assertDoesNotExist()

        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("最新消息").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        compose.waitForIdle()
        assertTranscriptBehindGlass()
    }

    private fun assertAgentChipHasNoArrow(label: String) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
        val chip = compose.onNodeWithText(label).getBoundsInRoot()
        val trailing = chip.right - text.right
        assertTrue("$label chip keeps a trailing arrow ($trailing)", trailing < 32.dp)
    }

    private fun assertTranscriptBehindGlass() {
        val toolbar = toolbarBounds()
        val composer = composerBounds()
        val transcript = compose.onNodeWithTag("conversation-transcript").getBoundsInRoot()
        assertTrue("transcript must extend behind toolbar glass", transcript.top <= toolbar.top)
        assertTrue("transcript must extend behind composer glass", transcript.bottom >= composer.bottom)
        val lines = compose.onAllNodesWithText("transcript-line", substring = true)
        val count = lines.fetchSemanticsNodes().size
        var visible = 0
        repeat(count) { index ->
            val bounds = lines[index].getBoundsInRoot()
            if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return@repeat
            if (bounds.top >= toolbar.bottom && bounds.bottom <= composer.top) visible++
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
