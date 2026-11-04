package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassPanelInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `glass panel buttons keep full width targets and dispatch once`() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = MobbyDarkScheme) {
                GlassControlScope(MobbyColors.Dark.drawer) {
                    Column(Modifier.width(280.dp)) {
                        Button({ events += "filled" }, Modifier.fillMaxWidth()) { androidx.compose.material3.Text("filled") }
                        TextButton({ events += "text" }, Modifier.fillMaxWidth()) { androidx.compose.material3.Text("text") }
                        OutlinedButton({ events += "outlined" }, Modifier.fillMaxWidth()) { androidx.compose.material3.Text("outlined") }
                        FilledTonalButton({ events += "tonal" }, Modifier.fillMaxWidth()) { androidx.compose.material3.Text("tonal") }
                        Button({ events += "disabled-button" }, Modifier.fillMaxWidth(), enabled = false) { androidx.compose.material3.Text("disabled-button") }
                        MenuAction("action") { events += "action" }
                        MenuAction("disabled", enabled = false) { events += "disabled" }
                    }
                }
            }
        }
        for (label in listOf("filled", "text", "outlined", "tonal", "action")) {
            compose.onNodeWithText(label).performTouchInput { click(Offset(width - 4f, height / 2f)) }
        }
        compose.onNodeWithText("disabled-button").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("disabled").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(listOf("filled", "text", "outlined", "tonal", "action"), events)
    }

    @Test fun `glass menu options retain selected and disabled semantics`() {
        val selected = mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = MobbyDarkScheme) {
                GlassControlScope(MobbyColors.Dark.drawer) {
                    Column(Modifier.width(280.dp)) {
                        MenuOption("option", selected.value) { selected.value = true; clicks++ }
                        MenuOption("unavailable", false, enabled = false) { clicks++ }
                    }
                }
            }
        }
        compose.onNodeWithText("option").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("unavailable").assertIsNotEnabled()
        assertEquals(1, clicks)
    }
}
