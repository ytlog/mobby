package com.github.ytlog.mobby.android.conversation.ui

import androidx.activity.ComponentActivity
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayProfile
import com.github.ytlog.mobby.android.deviceoperation.ConversationDevicePermissionDialog
import com.github.ytlog.mobby.android.runtime.api.device.DeviceAttention
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperation
import com.github.ytlog.mobby.android.runtime.api.device.DeviceRecord
import com.github.ytlog.mobby.android.runtime.api.device.DeviceStatus
import com.github.ytlog.mobby.android.runtime.api.device.DeviceSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.graph.AndroidScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import org.junit.After
import org.junit.Before
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

    private var previousController: com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort? = null

    @Before fun selectCurrentScreen() {
        previousController = ScreenUi.controller
        ScreenUi.controller = AndroidScreenControl(compose.activity)
        ScreenControl.select(compose.activity, ScreenMode.CURRENT)
    }

    @After fun restoreController() {
        ScreenUi.controller = previousController
    }

    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args ?: emptyArray()) } as T

    private fun viewModel(plugin: () -> Plugin, written: MutableList<String>, saveDirectory: (String) -> OperationResult = { error("unexpected directory grant") }): Pair<ConversationViewModel, Conversation> {
        val conversation = Conversation(ConversationId("c"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val conversations = MutableStateFlow(ConversationState(loading = false, selected = ConversationDetail(conversation, emptyList())))
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> flowOf(DiagnosticOutput(null, emptyList()))
            "agents" -> emptyList<AgentOption>()
            "nativeAccounts", "connectionSettings" -> emptyList<Any>()
            "gateways" -> emptyList<GatewayProfile>()
            "defaultGateway" -> null
            "plugins" -> DataResult.Loaded(listOf(plugin()))
            "saveDeviceDirectory" -> saveDirectory(args[0] as String)
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, args -> when {
            name == "getState" -> conversations
            name.startsWith("setSkill") -> { written += args[1] as String; Unit }
            else -> error(name)
        } }
        val vm = ConversationViewModel(ConversationUseCases(repository, stub { name, _ -> error(name) }, system, { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { name, _ -> error(name) }))
        return vm to conversation
    }

    @Test fun `picked directory is saved through the use case before adding the capability`() {
        val written = mutableListOf<String>()
        val location = "content://fixture.documents/tree/chosen"
        var available = false
        var saved: String? = null
        val ref = "plugin:device:storage"
        val (vm, conversation) = viewModel({ Plugin(ref, "文件", "选择目录", available, null, access = PluginAccess.DOCUMENT_TREE) }, written) {
            saved = it; available = true; OperationResult.Done
        }
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet(), onSelect = rememberDirectPluginSelector(vm, conversation)) } }
        compose.onNodeWithText("访问文件").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        val picker = shadow.nextStartedActivityForResult.intent
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)
        compose.runOnIdle { shadow.receiveResult(picker, android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.parse(location))) }
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(location, saved)
        assertEquals(listOf(ref), written)
    }

    @Test fun `refused directory grant reports failure and does not add the capability`() {
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:storage", "文件", "选择目录", false, null, access = PluginAccess.DOCUMENT_TREE) }, written) {
            OperationResult.Failed("fixture directory permission denied")
        }
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet(), onSelect = rememberDirectPluginSelector(vm, conversation)) } }
        compose.onNodeWithText("访问文件").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        val picker = shadow.nextStartedActivityForResult.intent
        compose.runOnIdle { shadow.receiveResult(picker, android.app.Activity.RESULT_OK,
            android.content.Intent().setData(android.net.Uri.parse("content://fixture.documents/tree/chosen"))) }
        var feedback: String? = null
        compose.waitUntil(5_000) { vm.feedback.tryReceive().getOrNull()?.let { feedback = it }; feedback != null }
        assertEquals("fixture directory permission denied", feedback)
        assertTrue(written.isEmpty())
    }

    @Test fun `first screen selection opens the mobby guide even if accessibility is available`() {
        val context = compose.activity
        context.getSharedPreferences("screen-control", 0).edit().clear().commit()
        ScreenControl.initialize(context)
        val written = mutableListOf<String>()
        val ref = "plugin:device:screen"
        val (vm, conversation) = viewModel({ Plugin(ref, "屏幕", "AI 操作手机", true, null) }, written)
        compose.setContent { MaterialTheme { EmptyConversationPlugins(emptySet(), onSelect = rememberDirectPluginSelector(vm, conversation)) } }
        compose.onNodeWithText("AI 操作手机").performClick()
        val shadow = Shadows.shadowOf(context)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        val guide = shadow.nextStartedActivityForResult.intent
        assertEquals(ScreenControlSettingsActivity::class.java.name, guide.component?.className)
        assertTrue(written.isEmpty())
        compose.runOnIdle { shadow.receiveResult(guide, android.app.Activity.RESULT_CANCELED, null) }
        compose.waitForIdle()
        assertTrue(written.isEmpty())

        compose.onNodeWithText("AI 操作手机").performClick()
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        val completed = shadow.nextStartedActivityForResult.intent
        compose.runOnIdle { shadow.receiveResult(completed, android.app.Activity.RESULT_OK, null) }
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(ref), written)
    }

    @Test fun `available plugin attaches to the current conversation from its card`() {
        val written = mutableListOf<String>()
        val ref = "plugin:device:media"
        val (vm, conversation) = viewModel({ Plugin(ref, "照片", "查看照片", true, null) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), onSelect = select)
            }
        }
        compose.onNodeWithText("查看照片").performClick()
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(ref), written)
    }

    @Test fun `unavailable photos request its permission on the conversation instead of opening catalogue`() {
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:media", "照片", "允许读取照片", false, "需要照片权限", access = PluginAccess.RUNTIME,
            permissions = listOf(android.Manifest.permission.READ_MEDIA_IMAGES)) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), onSelect = select)
            }
        }
        compose.onNodeWithText("查看照片").performClick()
        compose.onNodeWithText("允许读取照片").assertIsDisplayed()
        assertTrue(written.isEmpty())
    }

    @Test fun `phone card opens the same guide even when the saved current screen is available`() {
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:screen", "屏幕", "读取屏幕", true, null,
            access = PluginAccess.ACCESSIBILITY) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), onSelect = select)
            }
        }
        compose.onNodeWithText("AI 操作手机").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        val guide = shadow.nextStartedActivityForResult.intent
        assertEquals(ScreenControlSettingsActivity::class.java.name, guide.component?.className)
        compose.runOnIdle { shadow.receiveResult(guide, android.app.Activity.RESULT_CANCELED, null) }
        compose.waitForIdle()
        assertTrue(written.isEmpty())
    }

    @Test fun `an agent requesting screen access opens the ADB guide even with current screen saved`() {
        val context = compose.activity
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val (vm, conversation) = viewModel({ error("unexpected plugin lookup") }, mutableListOf())
        val operation = DeviceOperation("operation", "request", 1, "screen", "snapshot", "screen_control",
            DeviceStatus.AWAITING_USER, "device.authorize", DeviceSubject(), JsonObject(emptyMap()),
            requiresAttention = DeviceAttention("device.authorize", "prompt", listOf("confirm", "cancel")))
        val turn = Turn(TurnId("turn"), "操作手机", ExecutionId("execution"), ExecutionPhase.RUNNING, occupied = true,
            deviceOperations = listOf(DeviceRecord(operation, "fingerprint", 1)))
        compose.setContent { MaterialTheme { ConversationDevicePermissionDialog(ConversationDetail(conversation, listOf(turn)), vm) } }
        compose.onNodeWithText("设置推荐的屏幕模式").performClick()
        val guide = Shadows.shadowOf(context).nextStartedActivityForResult.intent
        assertEquals(ScreenControlSettingsActivity::class.java.name, guide.component?.className)
        assertEquals(ScreenMode.CURRENT, ScreenControl.state.value.mode)
    }

    @Test fun `screen selection handles missing notifications in the guide before adding the capability`() {
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notifications).setNotificationsEnabled(false)
        val written = mutableListOf<String>()
        val (vm, conversation) = viewModel({ Plugin("plugin:device:screen", "屏幕", "读取屏幕", false, "需要通知权限",
            access = PluginAccess.ACCESSIBILITY) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), onSelect = select)
            }
        }
        compose.onNodeWithText("AI 操作手机").performClick()
        val shadow = Shadows.shadowOf(compose.activity)
        compose.waitUntil(5_000) { shadow.peekNextStartedActivityForResult() != null }
        assertEquals(ScreenControlSettingsActivity::class.java.name, shadow.nextStartedActivityForResult.intent.component?.className)
        assertTrue(written.isEmpty())
    }

    @Test fun `photos join the conversation when permission is granted`() {
        val written = mutableListOf<String>()
        var available = false
        val ref = "plugin:device:media"
        val (vm, conversation) = viewModel({ Plugin(ref, "照片", "允许读取照片", available,
            if (available) null else "需要照片权限", access = PluginAccess.RUNTIME,
            permissions = listOf(android.Manifest.permission.READ_MEDIA_IMAGES)) }, written)
        compose.setContent {
            MaterialTheme {
                val select = rememberDirectPluginSelector(vm, conversation)
                EmptyConversationPlugins(emptySet(), onSelect = select)
            }
        }
        compose.onNodeWithText("查看照片").performClick()
        compose.onNodeWithText("允许读取照片").assertIsDisplayed()
        compose.onNodeWithText("继续").performClick()
        val request = Shadows.shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(android.Manifest.permission.READ_MEDIA_IMAGES), request.requestedPermissions.toList())
        compose.runOnIdle {
            available = true
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        }
        compose.waitUntil(5_000) { written.isNotEmpty() }
        assertEquals(listOf(ref), written)
    }
}
