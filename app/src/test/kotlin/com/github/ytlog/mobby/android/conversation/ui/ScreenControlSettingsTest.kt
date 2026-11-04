package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenControlSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Before fun prepare() { ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT) }
    @After fun restore() { ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT) }

    @Test fun `current screen settings use the app light cards`() = checkPalette(dark = false, ScreenMode.CURRENT)
    @Test fun `ADB settings use the app dark cards`() = checkPalette(dark = true, ScreenMode.ADB)

    private fun checkPalette(dark: Boolean, mode: ScreenMode) {
        lateinit var view: View
        val controller = TestController(ScreenControlState(mode = mode, adbPaired = true))
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme) {
                Surface(Modifier.fillMaxSize()) { ScreenControlSettings(controller, onBack = {}) }
            }
        }
        val bounds = compose.onNodeWithText("当前屏幕（无障碍）").fetchSemanticsNode().boundsInRoot
        val image = compose.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val expected = if (dark) MobbyColors.Dark.card else MobbyColors.Light.card
        assertEquals(expected.toArgb(), image.asImageBitmap().toPixelMap()[view.width / 2, bounds.top.toInt() + 3].toArgb())
        System.getenv("MOBBY_SCREEN_SETTINGS_PREVIEW_DIR")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "screen-settings-${mode.name.lowercase()}-${if (dark) "dark" else "light"}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun `each mode has one selectable row with the selected state on its label`() {
        val controller = TestController()
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme) { ScreenControlSettings(controller, onBack = {}) } }
        compose.onAllNodes(isSelectable()).assertCountEquals(3)
        compose.onNodeWithText("当前屏幕（无障碍）").assertIsSelected()
        compose.onNodeWithText("虚拟屏幕（root）").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ScreenMode.ROOT, controller.state.value.mode) }
    }

    @Test fun `pairing keeps mode selection and connection disabled while cancellation remains available`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPairing = true))
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme) { ScreenControlSettings(controller, onBack = {}) } }
        compose.onNodeWithText("虚拟屏幕（root）").assertIsNotEnabled()
        compose.onNodeWithText("连接端口（留空自动发现）").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("连接", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("取消配对").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel-pairing"), controller.events) }
        compose.onNodeWithText("虚拟屏幕（root）").performScrollTo().assertIsEnabled()
    }

    @Test fun `connection passes the entered port before connecting and active tasks cannot disconnect`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB))
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme) { ScreenControlSettings(controller, onBack = {}) } }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("12345")
        compose.onNodeWithText("连接", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("port:12345", "connect"), controller.events) }
        compose.onNodeWithText("断开连接").assertIsEnabled()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(active = true) }
        compose.onNodeWithText("断开连接").assertIsNotEnabled()
        compose.onNodeWithText("当前屏幕（无障碍）").performScrollTo().assertIsNotEnabled()
    }

    private class TestController(initial: ScreenControlState = ScreenControlState()) : ScreenControlPort {
        override val state = MutableStateFlow(initial)
        val events = mutableListOf<String>()
        override suspend fun select(mode: ScreenMode) { state.value = state.value.copy(mode = mode) }
        override suspend fun setAdbPort(port: String) { events += "port:$port" }
        override suspend fun connect() { events += "connect"; state.value = state.value.copy(connected = true) }
        override suspend fun disconnect() { events += "disconnect"; state.value = state.value.copy(connected = false) }
        override fun startAdbPairing() { events += "start-pairing"; state.value = state.value.copy(adbPairing = true) }
        override fun cancelAdbPairing() { events += "cancel-pairing"; state.value = state.value.copy(adbPairing = false) }
        override suspend fun refreshPreview() = Unit
    }

    @Test fun `all modes remain selectable and virtual selection requires its own connection`() {
        compose.setContent { MaterialTheme { ScreenControlSettings(com.github.ytlog.mobby.android.graph.AndroidScreenControl(RuntimeEnvironment.getApplication()), onBack = {}) } }
        compose.onNodeWithText("当前屏幕（无障碍）").assertIsDisplayed()
        compose.onNodeWithText("虚拟屏幕（内置 ADB）").performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.ADB }
        compose.onNodeWithText("通过通知配对").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("连接端口（留空自动发现）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("虚拟屏幕（Shizuku）").assertDoesNotExist()
        compose.onNodeWithText("授予 Shizuku 权限").assertDoesNotExist()
        compose.onNodeWithText("权限后端未连接").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(ScreenMode.ADB, ScreenControl.state.value.mode) }
        compose.onNodeWithText("虚拟屏幕（root）").performScrollTo().performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.ROOT }
        compose.runOnIdle { assertEquals(ScreenMode.ROOT, ScreenControl.state.value.mode) }
        compose.onNodeWithText("当前屏幕（无障碍）").performScrollTo().performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.CURRENT }
        compose.onNodeWithText("打开无障碍设置").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ScreenMode.CURRENT, ScreenControl.state.value.mode) }
    }
}
