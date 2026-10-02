package com.github.ytlog.mobby.android.conversation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
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
class CameraPermissionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    @After fun cleanup() { scope.cancel() }

    @Test fun `taking a photo requests camera permission before creating a capture`() {
        val conversation = Conversation(ConversationId("photo"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "missing-gateway"))
        var captures = 0
        val repository = stub<ConversationStore> { name -> when (name) {
            "awaitAttachmentRecovery" -> Unit
            "conversation" -> conversation
            else -> error(name)
        } }
        val system = stub<SystemPort> { name -> when (name) {
            "capture" -> DataResult.Loaded<CameraCapture?>(null)
            "beginCapture" -> { captures++; error("Camera must not start before permission") }
            else -> error(name)
        } }
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { error(it) }, system, { "fixture" }, scope,
            stub<PreferencePort> { error(it) })
        val messages = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            val camera = rememberCameraCapture(actions, { error("No photo to import") }, messages::add)
            Button(onClick = { camera.start(conversation) }) { Text("拍照") }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("拍照").performClick()
        val request = Shadows.shadowOf(compose.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.CAMERA), request.requestedPermissions.toList())
        assertEquals(0, captures)
        compose.runOnIdle {
            compose.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        compose.waitUntil(5_000) { messages.isNotEmpty() }
        assertTrue(messages.single().contains("相机权限"))
        assertEquals(0, captures)
    }
}
