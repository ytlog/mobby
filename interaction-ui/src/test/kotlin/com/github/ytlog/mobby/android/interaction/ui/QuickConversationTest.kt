package com.github.ytlog.mobby.android.interaction.ui

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.domain.gateway.GatewayProfile
import com.github.ytlog.mobby.android.localization.FloatingStrings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickConversationTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun `floating conversation continues rendering after screen tool detaches and restores window`(): Unit =
        androidx.compose.ui.platform.WindowRecomposerPolicy.withFactory(androidx.compose.ui.platform.WindowRecomposerFactory.LifecycleAware) {
        val fixture = Fixture()
        fixture.publishTitle("before screen tool")
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val floating = FloatingConversationWindow(context, fixture.actions, {}, {})
        try {
            floating.show(ConversationId("c"))
            compose.onNodeWithText("before screen tool").assertIsDisplayed()
            val hidden = floating.hideForScreenOperation()
            hidden.close()
            fixture.publishTitle("reply received after screen tool")
            compose.onNodeWithText("reply received after screen tool").assertIsDisplayed()
        } finally {
            floating.close()
            fixture.close()
        }
    }

    @Test fun `phone card selects plugin inside floating conversation without launching app`() {
        val fixture = Fixture()
        var appOpened = false
        compose.setContent {
            FloatingConversationContent(fixture.vm, ConversationId("c"), {}, {}, { appOpened = true })
        }
        compose.onNodeWithText("使用手机").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("plugin"), fixture.events)
            assertFalse(appOpened)
            assertEquals(setOf("plugin:device:screen"), fixture.vm.state.value.selected!!.conversation.draft.capabilities)
            fixture.close()
        }
    }

    @Test fun `unavailable phone card shows reason inside floating conversation without launching app`() {
        val fixture = Fixture(available = false)
        var appOpened = false
        compose.setContent {
            FloatingConversationContent(fixture.vm, ConversationId("c"), {}, {}, { appOpened = true })
        }
        compose.onNodeWithText("使用手机").performScrollTo().performClick()
        compose.onNodeWithText("请开启无障碍").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(appOpened)
            assertTrue(fixture.events.isEmpty())
            fixture.close()
        }
    }

    @Test fun `compose conversation window detaches for nested screen operations and respects close`() {
        val fixture = Fixture()
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val window = SystemPetWindow(context)
        val floating = FloatingConversationWindow(context, fixture.actions, {}, {}, window)
        floating.show(ConversationId("c"))
        fixture.idle()
        assertTrue(floating.isOpen)
        assertTrue(window.attached)
        val first = floating.hideForScreenOperation()
        val second = floating.hideForScreenOperation()
        assertFalse(window.attached)
        floating.refresh()
        first.close()
        first.close()
        assertFalse(window.attached)
        second.close()
        assertTrue(window.attached)
        val hidden = floating.hideForScreenOperation()
        floating.close()
        hidden.close()
        assertFalse(window.attached)
        assertFalse(floating.isOpen)
        fixture.close()
    }

    @Test fun `screen shortcut adds existing plugin and submits saved draft once through normal queue`() {
        val fixture = Fixture(gated = true)
        fixture.vm.readScreen()
        fixture.vm.readScreen()
        fixture.releasePlugin()
        fixture.idle()
        assertEquals(listOf("plugin", "draft", "prepare"), fixture.events)
        assertEquals(FloatingStrings.screenPrompt, fixture.prepared?.text)
        assertEquals(setOf("plugin:device:screen"), fixture.prepared?.capabilities)
        assertFalse(fixture.vm.screenReading.value)
        fixture.close()
    }

    @Test fun `screen shortcut uses existing question and preserves attachments`() {
        val fixture = Fixture(text = "这张图片里的文字是什么？")
        fixture.vm.readScreen()
        fixture.idle()
        assertEquals("这张图片里的文字是什么？", fixture.prepared?.text)
        assertEquals(listOf("existing-image"), fixture.prepared?.attachments)
        fixture.close()
    }

    @Test fun `unavailable screen plugin keeps draft and does not submit`() {
        val fixture = Fixture(available = false, text = "保留这个问题")
        fixture.vm.readScreen()
        fixture.idle()
        assertTrue(fixture.events.isEmpty())
        assertEquals("保留这个问题", fixture.vm.composer.value.value.text)
        assertNull(fixture.prepared)
        assertEquals("请开启无障碍", fixture.vm.feedback.tryReceive().getOrNull())
        assertFalse(fixture.vm.screenReading.value)
        fixture.close()
    }

    @Test fun `opening existing conversation selects it without creating another`() {
        val fixture = Fixture()
        var ready: ConversationId? = null
        fixture.vm.openQuickConversation(ConversationId("c")) { ready = it }
        fixture.idle()
        assertEquals(listOf("select"), fixture.events)
        assertEquals(ConversationId("c"), ready)
        fixture.close()
    }

    private class Fixture(available: Boolean = true, text: String = "", gated: Boolean = false) {
        private val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.PI, "model", null, "default", "test"),
            draft = Draft(text = text, attachments = listOf("existing-image")))
        private val state = MutableStateFlow(InteractionState(selected = ConversationDetail(conversation, emptyList()), loading = false))
        val events = mutableListOf<String>()
        var prepared: Draft? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val pluginGate = if (gated) CompletableDeferred<Unit>() else null
        private val repository = stub<InteractionRepository> { name, args -> when {
            name == "getState" -> state
            name.startsWith("select-") -> { events += "select"; Unit }
            name.startsWith("setSkill-") -> {
                events += "plugin"
                updateDraft { it.copy(revision = it.revision + 1, capabilities = it.capabilities + "plugin:device:screen") }
                Unit
            }
            name.startsWith("editDraft-") -> {
                events += "draft"
                updateDraft { it.copy(revision = it.revision + 1, text = args[1] as String,
                    selectionStart = args[2] as Int, selectionEnd = args[3] as Int) }
                state.value.selected!!.conversation.draft
            }
            name.startsWith("prepareTurn-") -> {
                events += "prepare"
                prepared = state.value.selected!!.conversation.draft
                PrepareTurnResult.Rejected(Failure.UNAVAILABLE)
            }
            else -> error(name)
        } }
        private val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> emptyFlow<SystemStatus>()
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            "agents" -> emptyList<AgentOption>()
            "plugins" -> {
                val result = DataResult.Loaded(listOf(Plugin("plugin:device:screen", "屏幕", "", available,
                    if (available) null else "请开启无障碍")))
                if (pluginGate == null) result else {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args.last() as kotlin.coroutines.Continuation<Any?>
                    scope.launch { pluginGate.await(); continuation.resumeWith(Result.success(result)) }
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
                }
            }
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub { name, _ -> error(name) }, system,
            { "turn" }, scope, stub { name, _ -> when (name) {
                "getAppearance" -> MutableStateFlow(Appearance.SYSTEM)
                else -> error(name)
            } })
        val vm = ConversationViewModel(actions)
        private val store = ViewModelStore().apply { put("vm", vm) }
        init { idle(); events.clear() }
        fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
        fun releasePlugin() { pluginGate?.complete(Unit) }
        fun publishTitle(title: String) {
            val detail = state.value.selected!!
            state.value = state.value.copy(selected = detail.copy(conversation = detail.conversation.copy(title = title)))
            idle()
        }
        fun close() { store.clear(); scope.cancel() }
        private fun updateDraft(change: (Draft) -> Draft) {
            val detail = state.value.selected!!
            state.value = state.value.copy(selected = detail.copy(conversation = detail.conversation.copy(draft = change(detail.conversation.draft))))
        }
    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T
