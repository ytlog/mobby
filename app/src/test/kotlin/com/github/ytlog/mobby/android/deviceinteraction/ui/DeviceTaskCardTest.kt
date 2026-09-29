package com.github.ytlog.mobby.android.deviceinteraction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceTaskCardTest {
    @get:Rule val compose = createComposeRule()
    private fun operation(display: String = "message_send") = DeviceOperation("operation", "request", 1, "sms", "send", display,
        DeviceStatus.UNCONFIRMED, "unconfirmed", DeviceSubject(), buildJsonObject {
            put("recipientMasked", "1234"); put("body", "fixture body")
        }, result = DeviceResult("message_receipt", EffectState.SUBMITTED, buildJsonObject {
            put("submitted", true); put("sent", "unknown"); put("delivered", "unknown")
        }))
    @Test fun `render all templates at narrow width without dispatching actions`() {
        var display by mutableStateOf("basic")
        var calls = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(280.dp)) {
            DeviceTaskCard(operation(display).copy(status = DeviceStatus.RUNNING), DeviceCardActions(stop = { calls++ }, openResource = { calls++ }, respond = { calls++ }))
        } } }
        listOf("basic", "screen_control", "message_list", "message_send", "media_grid", "batch_change", "file_list", "file_transfer",
            "capture", "record_change", "measurement", "connection", "system_handoff", "unknown-future").forEach {
            compose.runOnIdle { display = it }
            compose.waitForIdle()
            compose.onRoot().assertExists()
        }
        compose.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun `terminal operation cannot dispatch stop or stale capture response`() {
        compose.setContent { MaterialTheme { DeviceTaskCard(operation().copy(availableActions = listOf(DeviceButton.STOP_RUN)),
            DeviceCardActions(stop = { error("terminal must not stop") })) } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1) // Expand only.
    }
    @Test fun `conversation operation folds on completion and can be reopened`() {
        var shown by mutableStateOf(operation().copy(status = DeviceStatus.RUNNING))
        compose.setContent { MaterialTheme { DeviceTaskCard(shown, DeviceCardActions()) } }
        compose.onNodeWithText("fixture body").assertExists()
        compose.runOnIdle { shown = shown.copy(status = DeviceStatus.SUCCEEDED) }
        compose.onNodeWithText("fixture body").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("fixture body").assertExists()
    }
    @Test fun `unavailable capability offers no execution action`() {
        compose.setContent { MaterialTheme { DeviceUnavailableCard("Candidate", "Not implemented") } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
