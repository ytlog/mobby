package com.github.ytlog.mobby.android.interaction.ui

import androidx.activity.ComponentActivity
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.domain.gateway.GatewayProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectPluginSelectorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T

    private fun viewModel(plugin: () -> Plugin, written: MutableList<String>): Pair<ConversationViewModel, Conversation> {
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val interaction = MutableStateFlow(InteractionState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        val system = stub<SystemPort> { name, _ -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "gateways" -> emptyList<GatewayProfile>()
            "plugins" -> DataResult.Loaded(listOf(plugin()))
            else -> error(name)
        } }
        val repository = stub<InteractionRepository> { name, args -> when {
            name == "getState" -> interaction
            name.startsWith("setSkill") -> { written += args[1] as String; Unit }
            else -> error(name)
        } }
        val vm = ConversationViewModel(InteractionUseCases(repository, stub { name, _ -> error(name) }, system, { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { name, _ -> error(name) }))
        return vm to conversation
    }

    @Test fun `available plugin attaches to the current conversation from its card`() {
        val written = mutableListOf<String>()
        val ref = "plugin:device:screen"
        val (vm, conversation) = viewModel({ Plugin(ref, "屏幕", "使用手机", true, null) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), select)
            }
        }
        compose.onNodeWithText("使用手机").performClick()
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(ref), written)
    }

    @Test fun `unavailable camera requests its permission on the conversation instead of opening catalogue`() {
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:camera", "相机", "允许拍照", false, "需要相机权限", access = PluginAccess.RUNTIME,
            permissions = listOf(android.Manifest.permission.CAMERA)) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), select)
            }
        }
        compose.onNodeWithText("使用相机").performClick()
        compose.onNodeWithText("允许拍照").assertIsDisplayed()
        assertTrue(written.isEmpty())
    }

    @Test fun `unavailable phone opens accessibility settings directly`() {
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:screen", "屏幕", "读取屏幕", false, "需要无障碍服务",
            access = PluginAccess.ACCESSIBILITY) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), select)
            }
        }
        compose.onNodeWithText("使用手机").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadow.nextStartedActivityForResult.intent.action)
        assertTrue(written.isEmpty())
    }

    @Test fun `camera joins the conversation when permission is granted`() {
        val written = mutableListOf<String>()
        var available = false
        val ref = "plugin:device:camera"
        val (vm, conversation) = viewModel({ Plugin(ref, "相机", "允许拍照", available,
            if (available) null else "需要相机权限", access = PluginAccess.RUNTIME,
            permissions = listOf(android.Manifest.permission.CAMERA)) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), select)
            }
        }
        compose.onNodeWithText("使用相机").performClick()
        compose.onNodeWithText("允许拍照").assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        val request = Shadows.shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(android.Manifest.permission.CAMERA), request.requestedPermissions.toList())
        compose.runOnIdle {
            available = true
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        }
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(ref), written)
    }
}
