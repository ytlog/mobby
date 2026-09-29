package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.material3.MaterialTheme
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginPageTest {
    @After fun resetLanguage() { LanguagePreferences.select(org.robolectric.RuntimeEnvironment.getApplication(), com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE) }
    @get:Rule val compose = createComposeRule()
    private val plugin = Plugin("plugin:device:screen", "屏幕", "读取并操作当前屏幕", false, "请在系统设置中开启 mobby 的“屏幕”无障碍服务", "phone", PluginAccess.ACCESSIBILITY)
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T

    @Test fun `unavailable plugin cannot join the draft until the catalogue reports ready`() {
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val conversations = MutableStateFlow(ConversationState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        var available = false
        val written = mutableListOf<Triple<String, String, Boolean>>()
        val system = stub<SystemPort> { name, _ -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "plugins" -> DataResult.Loaded(listOf(plugin.copy(available = available, unavailableReason = if (available) null else plugin.unavailableReason)))
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, args -> when {
            name == "getState" -> conversations
            name.startsWith("setSkill") -> {
                written += Triple(args[0].toString(), args[1] as String, args[2] as Boolean)
                val refs = if (args[2] as Boolean) conversation.draft.capabilities + (args[1] as String) else conversation.draft.capabilities - (args[1] as String)
                val updated = conversations.value.selected!!.conversation.copy(draft = conversation.draft.copy(capabilities = refs))
                conversations.value = conversations.value.copy(selected = ConversationDetail(updated, emptyList()))
                Unit
            }
            else -> error(name)
        } }
        val vm = ConversationViewModel(ConversationUseCases(repository, stub { name, _ -> error(name) }, system, { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { name, _ -> error(name) }))
        compose.setContent { MaterialTheme { PluginPage(vm) {} } }
        compose.onNodeWithText("屏幕").assertIsDisplayed()
        compose.onNodeWithText("手机").assertIsDisplayed()
        compose.onNodeWithText("开启").assertIsDisplayed()
        compose.onNodeWithText("使用").assertDoesNotExist()
        compose.onNodeWithText("沟通").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这个分类还没有插件").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("手机").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("屏幕").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            available = true
            vm.loadPlugins()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("使用").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("使用").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(Triple("c", plugin.ref, true)), written)
        compose.onNodeWithText("移除").assertIsDisplayed()
    }

    @Test fun `category tabs filter the catalogue`() {
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val conversations = MutableStateFlow(ConversationState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        val system = stub<SystemPort> { name, _ -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "plugins" -> DataResult.Loaded(listOf(plugin.copy(available = true, unavailableReason = null)))
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, _ -> if (name == "getState") conversations else error(name) }
        val vm = ConversationViewModel(ConversationUseCases(repository, stub { name, _ -> error(name) }, system, { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { name, _ -> error(name) }))
        compose.setContent { MaterialTheme { PluginPage(vm) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("屏幕").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { LanguagePreferences.select(org.robolectric.RuntimeEnvironment.getApplication(), com.github.ytlog.mobby.android.localization.AppLanguage.ENGLISH) }
        compose.onNodeWithText("Phone").assertIsDisplayed()
        compose.onNodeWithText("屏幕").assertIsDisplayed()
        compose.runOnIdle { LanguagePreferences.select(org.robolectric.RuntimeEnvironment.getApplication(), com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE) }
        compose.onNodeWithText("沟通").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("这个分类还没有插件").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("手机").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("屏幕").fetchSemanticsNodes().isNotEmpty() }
    }

}
