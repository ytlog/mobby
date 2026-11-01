package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.device.ScreenControl
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenControlSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Before fun prepare() { ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT) }
    @After fun restore() { ScreenControl.select(RuntimeEnvironment.getApplication(), ScreenMode.CURRENT) }

    @Test fun `all modes remain selectable and virtual selection requires its own connection`() {
        compose.setContent { MaterialTheme { ScreenControlSettings(com.github.ytlog.mobby.android.graph.AndroidScreenControl(RuntimeEnvironment.getApplication()), onBack = {}) } }
        compose.onNodeWithText("当前屏幕（无障碍）").assertIsDisplayed()
        compose.onNodeWithText("虚拟屏幕（Shizuku）").performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.SHIZUKU }
        compose.onNodeWithText("授予 Shizuku 权限").assertIsDisplayed()
        compose.onNodeWithText("权限后端未连接").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ScreenMode.SHIZUKU, ScreenControl.state.value.mode) }
        compose.onNodeWithText("虚拟屏幕（root）").performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.ROOT }
        compose.runOnIdle { assertEquals(ScreenMode.ROOT, ScreenControl.state.value.mode) }
        compose.onNodeWithText("当前屏幕（无障碍）").performClick()
        compose.waitUntil { ScreenControl.state.value.mode == ScreenMode.CURRENT }
        compose.onNodeWithText("打开无障碍设置").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ScreenMode.CURRENT, ScreenControl.state.value.mode) }
    }
}
