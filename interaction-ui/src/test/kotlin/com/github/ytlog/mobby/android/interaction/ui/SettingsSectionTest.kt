package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class SettingsSectionTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = ViewModelStore()

    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    @After fun cleanup() { compose.runOnIdle { store.clear() }; scope.cancel() }

    @Test fun `shell diagnostic and environment check share one section below storage`() {
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> MutableStateFlow(InteractionState(loading = false))
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true, "运行环境已就绪"))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.DARK)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        val vm = ConversationViewModel(actions).also { store.put("vm", it) }
        compose.setContent {
            MaterialTheme {
                SettingsPage(SystemStatus(true, true, "运行环境已就绪"), Appearance.DARK, {}, {}, {}, vm)
            }
        }
        val storage = compose.onNodeWithText("存储与保留").fetchSemanticsNode().boundsInRoot
        val shell = compose.onNodeWithText("Shell 诊断").fetchSemanticsNode().boundsInRoot
        val check = compose.onNodeWithText("重新检查运行环境").fetchSemanticsNode().boundsInRoot
        assertTrue(storage.bottom < shell.top)
        assertTrue(shell.bottom < check.top)
        assertTrue(check.top - shell.bottom < shell.top - storage.bottom)
    }

    @Test fun `desktop pet switch reports the requested value`() {
        var requested: Boolean? = null
        val vm = viewModel()
        compose.setContent {
            MaterialTheme {
                SettingsPage(SystemStatus(true, true, "运行环境已就绪"), Appearance.DARK, {}, {}, {}, vm, setPet = { requested = it })
            }
        }
        compose.onNodeWithText("桌面悬浮球").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(true, requested)
        compose.onNodeWithText("离开应用后显示悬浮球。点按可返回应用；有任务执行时可停止任务或回到对话，拖动可调整位置。").performScrollTo().assertIsDisplayed()
    }

    @Test fun `desktop pet explains a missing overlay permission`() {
        val vm = viewModel()
        compose.setContent {
            MaterialTheme {
                SettingsPage(SystemStatus(true, true, "运行环境已就绪"), Appearance.DARK, {}, {}, {}, vm, petEnabled = true, petPermitted = false)
            }
        }
        compose.onNodeWithText("需要允许显示在其他应用的上层。").performScrollTo().assertIsDisplayed()
    }

    @Test fun `language can be changed immediately in settings and switched back`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        LanguagePreferences.select(context, com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE)
        val vm = viewModel()
        compose.setContent { MaterialTheme {
            SettingsPage(SystemStatus(true, true), Appearance.DARK, {}, {}, {}, vm)
        } }
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Language").assertIsDisplayed()
        compose.onNodeWithText("中文").performScrollTo().performClick()
        compose.onNodeWithText("设置").assertIsDisplayed()
        assertEquals("zh", context.getSharedPreferences("mobby.language", 0).getString("language", null))
    }

    private fun viewModel(): ConversationViewModel {
        val repository = stub<InteractionRepository> { name -> when (name) {
            "getState" -> MutableStateFlow(InteractionState(loading = false))
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true, "运行环境已就绪"))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            else -> error(name)
        } }
        val preferences = stub<PreferencePort> { name -> when (name) {
            "getAppearance" -> MutableStateFlow(Appearance.DARK)
            else -> error(name)
        } }
        val actions = InteractionUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope, preferences)
        return ConversationViewModel(actions).also { store.put(it.toString(), it) }
    }
}
