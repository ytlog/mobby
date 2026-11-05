package com.github.ytlog.mobby.android.conversation.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenControlSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val ready = ScreenSetupStatus(true, true, true, true, true)
    private val opened = mutableListOf<ScreenSettingsTarget>()
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction())
    private fun next() = button("下一步").performClick()
    private fun show(controller: TestController, status: ScreenSetupStatus = ready, finished: () -> Unit = {}) {
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme) {
            ScreenControlSettings(controller, onBack = {}, onFinished = finished, statusOverride = status, settingsOverride = { opened += it })
        } }
    }
    @Before fun prepare() {
        ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun restore() { ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT) }

    @Test fun `choosing a screen mode does not change configuration or show later tasks`() {
        val controller = TestController()
        show(controller)
        compose.onNodeWithText("虚拟屏幕（内置 ADB）").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ScreenMode.CURRENT, controller.state.value.mode) }
        compose.onNodeWithText("通过通知配对").assertDoesNotExist()
        compose.onNodeWithText("手动填写连接端口").assertDoesNotExist()
        next()
        compose.runOnIdle { assertEquals(ScreenMode.ADB, controller.state.value.mode); assertFalse(controller.state.value.connected) }
        compose.onNodeWithText("虚拟屏幕（root）").assertDoesNotExist()
    }
    @Test fun `prerequisites are separate pages and are rechecked when returning`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB))
        val status = mutableStateOf(ScreenSetupStatus(false, false, false, false, false))
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = { opened += it }) } }
        next()
        button("允许通知").assertIsDisplayed().performClick()
        compose.onNodeWithText("开启开发者模式").assertDoesNotExist()
        compose.onNodeWithText("连接 Wi-Fi").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(ScreenSettingsTarget.NOTIFICATIONS), opened); status.value = status.value.copy(notificationsAllowed = true) }
        button("连接 Wi-Fi").assertIsDisplayed().performClick()
        compose.onNodeWithText("开启无线调试").assertDoesNotExist()
        compose.runOnIdle { status.value = status.value.copy(wifiConnected = true) }
        button("打开关于手机").assertIsDisplayed().performClick()
        compose.runOnIdle { status.value = status.value.copy(developerEnabled = true) }
        button("打开无线调试").assertIsDisplayed().performClick()
        compose.runOnIdle { status.value = status.value.copy(wirelessEnabled = true) }
        button("通过通知配对").assertIsDisplayed()
        compose.onNodeWithText("手动填写连接端口").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), controller.events) }
    }
    @Test fun `current screen only asks for accessibility after completed notifications`() {
        val controller = TestController()
        val status = mutableStateOf(ready.copy(accessibilityEnabled = false))
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = { opened += it }) } }
        next()
        button("打开无障碍设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("开启开发者模式").assertDoesNotExist()
        compose.runOnIdle { status.value = status.value.copy(accessibilityEnabled = true) }
        compose.onNodeWithText("屏幕操作已就绪").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ScreenSettingsTarget.ACCESSIBILITY), opened) }
    }
    @Test fun `a saved ready pairing skips completed pages and connects only on request`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true))
        var finished = false
        show(controller, finished = { finished = true })
        next()
        compose.onNodeWithContentDescription("第 7 步：连接").assertIsSelected()
        button("连接").assertIsDisplayed()
        compose.onNodeWithText("配对这台手机").assertDoesNotExist()
        compose.runOnIdle { assertTrue(controller.events.isEmpty()) }
        button("连接").performClick()
        compose.onNodeWithText("屏幕操作已就绪").assertIsDisplayed()
        button("完成").performClick()
        compose.runOnIdle { assertTrue(finished); assertEquals(listOf("port:", "connect"), controller.events) }
    }
    @Test fun `embedded guide includes agent steps and auxiliary pages keep parent numbering`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true))
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = ready,
            settingsOverride = {}, embedded = true, stepOffset = 2) } }
        compose.onNodeWithContentDescription("第 1 步：选择一个 Agent").assertExists()
        compose.onNodeWithContentDescription("第 3 步：选择屏幕操作方式").assertIsSelected()
        next()
        compose.onNodeWithContentDescription("第 9 步：连接").assertIsSelected()
        button("手动填写连接端口").performClick()
        compose.onNodeWithContentDescription("第 9 步：连接").assertIsSelected()
    }
    @Test fun `manual port has its own page and does not connect when saved`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true))
        show(controller)
        next()
        button("手动填写连接端口").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("12345")
        compose.onNodeWithText("连接", substring = false).assertDoesNotExist()
        compose.onNodeWithText("通过通知配对").assertDoesNotExist()
        next()
        compose.runOnIdle { assertEquals(listOf("port:12345"), controller.events) }
        button("连接").performClick()
        compose.runOnIdle { assertEquals("12345", controller.port); assertEquals(1, controller.events.count { it == "connect" }) }
    }
    @Test fun `tabs review completed steps without executing or skipping an incomplete connection`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true))
        show(controller)
        next()
        compose.onNodeWithContentDescription("第 1 步：选择屏幕操作方式").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "步骤完成"))
        compose.onNodeWithContentDescription("第 7 步：连接").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前步骤"))
        compose.onNodeWithContentDescription("第 8 步：完成").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未完成"))
        compose.onNodeWithContentDescription("第 1 步：选择屏幕操作方式").performScrollTo().performClick()
        compose.onNodeWithText("虚拟屏幕（内置 ADB）").assertIsDisplayed()
        compose.onNodeWithContentDescription("第 7 步：连接").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第 5 步：开启无线调试").performScrollTo().performClick()
        compose.onNodeWithText("开启无线调试").assertIsDisplayed()
        compose.runOnIdle { assertTrue(controller.events.isEmpty()) }
        next()
        button("连接").assertIsDisplayed()
        compose.runOnIdle { assertTrue(controller.events.isEmpty()) }
    }
    @Test fun `review continues at the first missing prerequisite and pairing locks tabs`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true))
        val status = mutableStateOf(ready)
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = {}) } }
        next()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(adbPairing = true) }
        compose.onNodeWithContentDescription("第 1 步：选择屏幕操作方式").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "步骤完成"))
        compose.runOnIdle { controller.state.value = controller.state.value.copy(adbPairing = false); status.value = ready.copy(wirelessEnabled = false) }
        compose.onNodeWithContentDescription("第 5 步：开启无线调试").assertIsNotEnabled()
        compose.onNodeWithContentDescription("第 3 步：连接 Wi-Fi").performScrollTo().performClick()
        next()
        button("打开无线调试").assertIsDisplayed()
        compose.runOnIdle { assertTrue(controller.events.isEmpty()) }
    }
    @Test fun `user started pairing connects once only after a real success and foreground return`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB))
        val status = mutableStateOf(ready)
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = { opened += it }) } }
        next()
        button("通过通知配对").performClick()
        compose.runOnIdle {
            status.value = status.value.copy(resumed = false)
            controller.state.value = controller.state.value.copy(adbPaired = true, adbPairing = false, adbPairingSuccess = 1)
        }
        compose.runOnIdle { assertEquals(listOf("start-pairing"), controller.events); status.value = status.value.copy(resumed = true) }
        compose.onNodeWithText("屏幕操作已就绪").assertIsDisplayed()
        compose.runOnIdle { status.value = status.value.copy(resumed = false) }
        compose.runOnIdle { status.value = status.value.copy(resumed = true) }
        compose.runOnIdle { assertEquals(listOf("start-pairing", "port:", "connect"), controller.events) }
    }
    @Test fun `failed automatic connection stays on connection task without retry or inline inputs`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB), "fixture connection failed")
        show(controller)
        next()
        button("通过通知配对").performClick()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(adbPaired = true, adbPairing = false, adbPairingSuccess = 1) }
        compose.onNodeWithText("fixture connection failed").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("配对这台手机").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, controller.events.count { it == "connect" }) }
    }
    @Test fun `connection failure guides disabled developer settings before wireless debugging`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true), "fixture connection failed")
        val status = mutableStateOf(ready)
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = { opened += it }) } }
        next()
        compose.runOnIdle { status.value = ready.copy(developerEnabled = false, wirelessEnabled = false) }
        button("连接").performClick()
        button("打开关于手机").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, controller.events.count { it == "connect" }); status.value = ready.copy(wirelessEnabled = false) }
        button("打开无线调试").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(ScreenSettingsTarget.ABOUT, ScreenSettingsTarget.WIRELESS), opened) }
    }
    @Test fun `connection failure opens wireless settings when developer mode is enabled`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true), "fixture connection failed")
        show(controller)
        next()
        button("连接").performClick()
        compose.runOnIdle { assertEquals(listOf(ScreenSettingsTarget.WIRELESS), opened); assertEquals(1, controller.events.count { it == "connect" }) }
        button("手动填写连接端口").assertIsDisplayed()
    }
    @Test fun `cancelled or interrupted pairing never uses an old identity as new success`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPairing = true, adbPaired = true))
        show(controller)
        button("取消配对").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("cancel-pairing"), controller.events) }
        next()
        button("连接").assertIsDisplayed()
        compose.runOnIdle { assertFalse(controller.events.contains("connect")) }
    }
    @Test fun `an interrupted re-pairing never auto retries connection using the saved identity`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true), "fixture connection failed")
        show(controller)
        next()
        button("连接").performClick()
        button("重新配对").performClick()
        button("通过通知配对").performClick()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(adbPairing = false) }
        compose.onNodeWithText("配对未完成，可以重新开始。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, controller.events.count { it == "connect" }); assertTrue(controller.state.value.adbPaired) }
    }
    @Test @Config(sdk = [34], qualifiers = "w320dp-h480dp-mdpi")
    fun `navigation remains visible on a small screen without scrolling to the footer`() {
        show(TestController(), ready.copy(accessibilityEnabled = false))
        button("下一步").assertIsDisplayed().performClick()
        button("打开无障碍设置").assertIsDisplayed()
        button("上一步").assertIsDisplayed()
    }
    @Test fun `brand selection is a separate task and back returns to developer instructions`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB))
        show(controller, ready.copy(developerEnabled = false))
        next()
        button("查看其他系统的操作方法").performClick()
        compose.onNodeWithText("Samsung · One UI").performClick()
        compose.onNodeWithText("打开关于手机").assertDoesNotExist()
        next()
        compose.onNodeWithText("Samsung · One UI").assertIsDisplayed()
        button("打开关于手机").assertIsDisplayed()
        compose.onNodeWithText("开启无线调试").assertDoesNotExist()
    }
    @Test fun `completed previous steps stay visible until explicitly continuing again`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB))
        val status = mutableStateOf(ready.copy(wifiConnected = false, developerEnabled = false))
        compose.setContent { MaterialTheme { ScreenControlSettings(controller, {}, statusOverride = status.value, settingsOverride = {}) } }
        next()
        button("连接 Wi-Fi").performClick()
        compose.runOnIdle { status.value = status.value.copy(wifiConnected = true) }
        button("打开关于手机").assertIsDisplayed()
        button("上一步").performClick()
        compose.onNodeWithText("连接 Wi-Fi").assertIsDisplayed()
        button("下一步").assertIsDisplayed()
        compose.onNodeWithText("开启开发者模式").assertDoesNotExist()
    }
    @Test fun `active tasks prevent changing the mode or disconnecting`() {
        val controller = TestController(ScreenControlState(mode = ScreenMode.ADB, adbPaired = true, connected = true))
        show(controller)
        next()
        button("断开连接").performClick()
        compose.runOnIdle { controller.state.value = controller.state.value.copy(active = true) }
        button("断开连接").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(controller.events.isEmpty()) }
    }
    @Test fun `single step cards keep the app palette in both themes`() {
        lateinit var view: View
        val dark = mutableStateOf(false)
        val controller = TestController()
        compose.setContent {
            val host = LocalView.current
            SideEffect { view = host }
            MaterialTheme(colorScheme = if (dark.value) MobbyDarkScheme else MobbyLightScheme) {
                Surface(Modifier.fillMaxSize()) { ScreenControlSettings(controller, {}, statusOverride = ready) }
            }
        }
        for (isDark in listOf(false, true)) {
            compose.runOnIdle { dark.value = isDark }
            val bounds = compose.onNodeWithText("当前屏幕（无障碍）").fetchSemanticsNode().boundsInRoot
            val bitmap = compose.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
            assertEquals((if (isDark) MobbyColors.Dark.card else MobbyColors.Light.card).toArgb(), bitmap.asImageBitmap().toPixelMap()[view.width / 2, bounds.top.toInt() + 3].toArgb())
            System.getenv("MOBBY_STEP_PREVIEW_DIR")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "screen-mode-${if (isDark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    private class TestController(initial: ScreenControlState = ScreenControlState(), val failure: String? = null) : ScreenControlPort {
        override val state = MutableStateFlow(initial)
        val events = mutableListOf<String>()
        var port = ""
        override suspend fun select(mode: ScreenMode) { state.value = state.value.copy(mode = mode) }
        override suspend fun setAdbPort(port: String) { events += "port:$port"; this.port = port }
        override suspend fun connect() { events += "connect"; failure?.let { error(it) }; state.value = state.value.copy(connected = true) }
        override suspend fun disconnect() { events += "disconnect"; state.value = state.value.copy(connected = false) }
        override fun startAdbPairing() { events += "start-pairing"; state.value = state.value.copy(adbPairing = true, error = null) }
        override fun cancelAdbPairing() { events += "cancel-pairing"; state.value = state.value.copy(adbPairing = false) }
        override suspend fun refreshPreview() = Unit
    }
}
