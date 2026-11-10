package com.github.ytlog.mobby.android.deviceoperation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.ui.UiStrings
import com.github.ytlog.mobby.android.device.PrivateScreenInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateScreenInputPromptTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aHandoffWithAFocusedFieldIsAnInputDialogWithAWayIntoTheTakeoverPage() {
        var filled: List<String>? = null
        val request = PrivateScreenInput.Request(listOf(PrivateScreenInput.Field("手机号")), "抖音登录页需要你的手机号", handoff = true)
        compose.setContent {
            MaterialTheme {
                PrivateScreenInputPrompt(request, canTakeOver = true, onFill = { filled = it; true }, onTakeOver = { true }, onCancel = {})
            }
        }
        compose.onNodeWithText(UiStrings.virtualScreenInputTitle("手机号")).assertIsDisplayed()
        compose.onNodeWithText("抖音登录页需要你的手机号").assertIsDisplayed()
        compose.onNodeWithText(UiStrings.virtualScreenInputPrivacy).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("fixture-number")
        compose.onNodeWithText(UiStrings.virtualScreenManualFill).performClick()
        assertEquals(listOf("fixture-number"), filled)
        compose.onNodeWithText(UiStrings.virtualScreenEnterTakeover).assertIsDisplayed()
    }

    @Test fun aHandoffWithoutAFieldOnlyOffersTheTakeoverPage() {
        var tookOver = false
        val request = PrivateScreenInput.Request(emptyList(), "", handoff = true)
        compose.setContent {
            MaterialTheme {
                PrivateScreenInputPrompt(request, canTakeOver = true, onFill = { true }, onTakeOver = { tookOver = true; true }, onCancel = {})
            }
        }
        compose.onNodeWithText(UiStrings.virtualScreenOperateTitle).assertIsDisplayed()
        compose.onNodeWithText(UiStrings.virtualScreenEnterTakeover).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.virtualScreenManualFill).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.virtualScreenEnterTakeover).performClick()
        assertTrue(tookOver)
    }
}
