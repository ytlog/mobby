package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.interaction.domain.PermissionRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionUiTest {
    @get:Rule val compose = createComposeRule()
    private val permission = PermissionRequest("p", 7, "Write", "{\"file_path\":\"/fixture/file\",\"content\":\"literal\"}")
    @Test fun `display and recomposition do not authorize and buttons send explicit one time choices`() {
        val choices = mutableListOf<Boolean>()
        var busy by mutableStateOf(false)
        compose.setContent { MaterialTheme { PermissionContent(permission, !busy, busy, false, true) { choices += it; busy = true } } }
        compose.onNodeWithText("需要你的授权").assertIsDisplayed()
        compose.onNodeWithText("写入 /fixture/file").assertExists()
        compose.onNodeWithText("文件\n/fixture/file\n\n内容\nliteral", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(permission.scope).assertDoesNotExist()
        assertTrue(choices.isEmpty())
        compose.onNodeWithText("仅允许这一次").performClick()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
        compose.onNodeWithText("仅允许这一次").assertIsNotEnabled()
        assertEquals(listOf(true), choices)
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("拒绝").performClick()
        assertEquals(listOf(true, false), choices)
    }
    @Test fun `disconnected and submitted requests expose status without enabled authorization`() {
        var connected by mutableStateOf(false)
        var submitted by mutableStateOf(false)
        compose.setContent { MaterialTheme { PermissionContent(permission, false, false, submitted, connected) { fail("disabled decision") } } }
        compose.onNodeWithText("连接中断，恢复连接后再确认").assertIsDisplayed()
        compose.onNodeWithText("仅允许这一次").assertIsNotEnabled()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
        compose.runOnIdle { connected = true; submitted = true }
        compose.onNodeWithText("决定已接纳，等待执行结果").assertIsDisplayed()
        compose.onNodeWithText("仅允许这一次").assertIsNotEnabled()
    }
    @Test @Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
    fun `long scope stays complete and both decisions remain reachable in a short viewport`() {
        val scope = (1..100).joinToString("\n") { "parameter-$it" }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { PermissionContent(permission.copy(scope = scope), true, false, false, true) {} } } }
        compose.onNodeWithText(scope).assertExists()
        compose.onNodeWithText("拒绝").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅允许这一次").performScrollTo().assertIsDisplayed()
    }
}
