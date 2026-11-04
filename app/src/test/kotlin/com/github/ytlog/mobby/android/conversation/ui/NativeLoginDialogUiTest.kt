package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ClipboardManager
import android.content.Context
import android.view.inspector.WindowInspector
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeLoginDialogUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = ViewModelStore()
    private var loginCancelled = false
    private var loginUpdate = AgentLoginUpdate()
    private val methods = listOf(AgentLoginMethod("browser", "ChatGPT"), AgentLoginMethod("device", "ChatGPT · Device code"))
    private inline fun <reified T> stub(crossinline body: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> body(method.name, args.orEmpty()) } as T
    private fun vm(): ConversationViewModel {
        val system = stub<SystemPort> { name, args -> when (name) {
            "getStatus" -> flowOf(SystemStatus(true, true))
            "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
            "nativeAccounts", "connectionSettings", "gateways", "agents" -> emptyList<Any>()
            "defaultGateway" -> null
            "nativeLoginMethods" -> DataResult.Loaded(methods)
            "loginAgent" -> {
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<OperationResult>
                @Suppress("UNCHECKED_CAST") val report = args[2] as (AgentLoginUpdate) -> Unit
                val operation: suspend () -> OperationResult = {
                    try {
                        report(loginUpdate)
                        awaitCancellation()
                    } finally { loginCancelled = true }
                }
                operation.startCoroutine(continuation)
                COROUTINE_SUSPENDED
            }
            else -> error(name)
        } }
        val repository = stub<ConversationStore> { name, _ -> when (name) {
            "getState" -> MutableStateFlow(ConversationState(loading = false))
            else -> error(name)
        } }
        val actions = ConversationUseCases(repository, stub<ExecutionPort> { name, _ -> error(name) }, system, { "fixture" }, scope,
            stub<PreferencePort> { name, _ -> when (name) { "getAppearance" -> MutableStateFlow(Appearance.DARK); else -> error(name) } })
        return ConversationViewModel(actions).also { owner.put("vm", it) }
    }
    @After fun cleanup() { compose.runOnIdle { owner.clear() }; scope.cancel() }
    private fun show(dark: Boolean) {
        val vm = vm()
        compose.setContent { MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme) {
            AgentConnectionsPage(vm, initialAgent = AgentId.CODEX, back = {}, gateway = {})
        } }
        compose.onNodeWithText("ChatGPT · Device code").assertIsDisplayed()
    }
    private fun checkSurface(expected: Color, name: String) {
        val bounds = compose.onNodeWithTag("native-login-dialog").fetchSemanticsNode().boundsInWindow
        val image = compose.runOnIdle {
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888).also { bitmap ->
                WindowInspector.getGlobalWindowViews().last().draw(Canvas(bitmap).apply { translate(-bounds.left, -bounds.top) })
            }
        }
        val pixels = image.asImageBitmap().toPixelMap()
        assertEquals("The authorization surface must use the app card palette", expected.toArgb(), pixels[pixels.width / 2, 8].toArgb())
        System.getenv("MOBBY_LOGIN_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `dark authorization dialog uses the same neutral card surface as the app`() {
        show(dark = true)
        checkSurface(MobbyColors.Dark.card, "native-login-dark")
    }
    @Test fun `light authorization dialog uses the same white card surface as the app`() {
        show(dark = false)
        checkSurface(MobbyColors.Light.card, "native-login-light")
    }
    @Test fun `cancel remains available and cancels the native login while authorization is pending`() {
        show(dark = true)
        compose.onNodeWithText("ChatGPT").performClick()
        compose.onNodeWithText("正在等待授权…").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsEnabled().performClick()
        compose.onNodeWithTag("native-login-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(loginCancelled) }
    }
    @Test fun `device authorization displays and copies the original code`() {
        loginUpdate = AgentLoginUpdate(code = "TEST-CODE", instructions = "Complete authorization in your browser.")
        show(dark = true)
        compose.onNodeWithText("ChatGPT · Device code").performClick()
        compose.onNodeWithText("TEST-CODE").assertIsDisplayed()
        compose.onNodeWithText(loginUpdate.instructions!!).assertIsDisplayed()
        compose.onNodeWithText("复制原文").performClick()
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("TEST-CODE", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        checkSurface(MobbyColors.Dark.card, "native-login-device-code")
    }
}
