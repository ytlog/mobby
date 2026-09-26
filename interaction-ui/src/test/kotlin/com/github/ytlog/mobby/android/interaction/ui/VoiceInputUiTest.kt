package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class VoiceInputUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `tap mic enters hold to speak and keyboard returns to the text field`() {
        compose.setContent {
            var voice by remember { mutableStateOf(false) }
            MaterialTheme {
                bar(voiceMode = voice, onEnterVoice = { voice = true }, onExitVoice = { voice = false })
            }
        }
        compose.onNodeWithContentDescription("语音输入").performClick()
        compose.onNodeWithText("按住说话").assertIsDisplayed()
        compose.onNodeWithContentDescription("键盘输入").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("键盘输入").performClick()
        compose.onNodeWithText("描述任务，或添加上下文").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
    }

    @Test fun `running agent keeps stop available and shows append action when draft has text`() {
        val events = mutableListOf<String>()
        compose.setContent { MaterialTheme { bar(stop = true, sendEnabled = true,
            onSend = { events += "append" }, onStop = { events += "stop" }) } }
        compose.onNodeWithContentDescription("追加消息").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("停止当前任务").assertIsDisplayed().performClick()
        assertEquals(listOf("append", "stop"), events)
    }

    @Test fun `short press asks to hold and long press arms cancel before release`() {
        val events = mutableListOf<String>()
        compose.setContent {
            var recording by remember { mutableStateOf(false) }
            var armed by remember { mutableStateOf(false) }
            MaterialTheme {
                bar(
                    voiceMode = true,
                    recording = recording,
                    cancelArmed = armed,
                    onHoldTap = { events += "tap" },
                    onHoldStart = { recording = true; events += "start" },
                    onHoldMove = { armed = it; events += if (it) "arm" else "clear" },
                    onHoldEnd = { events += if (it) "cancel" else "send"; recording = false; armed = false },
                )
            }
        }
        compose.onNodeWithTag("hold-to-speak").performTouchInput {
            down(center)
            advanceEventTime(40)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("tap"), events)
        compose.onNodeWithText("按住说话").assertIsDisplayed()

        events.clear()
        compose.onNodeWithTag("hold-to-speak").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 80)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("start", "send"), events)

        events.clear()
        compose.onNodeWithTag("hold-to-speak").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 80)
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 400f))
            up()
        }
        compose.waitForIdle()
        assertEquals("start", events.first())
        assertTrue(events.contains("arm"))
        assertEquals("cancel", events.last())
        compose.onNodeWithText("松手发送，上滑取消").assertDoesNotExist()
    }

    @Test fun `long press on the mic starts recording without leaving text mode`() {
        val events = mutableListOf<String>()
        compose.setContent {
            var voice by remember { mutableStateOf(false) }
            var recording by remember { mutableStateOf(false) }
            MaterialTheme {
                bar(
                    voiceMode = voice,
                    recording = recording,
                    onEnterVoice = { voice = true; events += "mode" },
                    onHoldStart = { recording = true; events += "start" },
                    onHoldEnd = { events += if (it) "cancel" else "send"; recording = false },
                )
            }
        }
        compose.onNodeWithTag("voice-mic").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 80)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("start", "send"), events)
        assertFalse(events.contains("mode"))
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
    }

    @Test fun `recording hint switches when cancel is armed`() {
        val armed = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { MaterialTheme { bar(voiceMode = true, recording = true, cancelArmed = armed.value) } }
        compose.onNodeWithText("松手发送，上滑取消").assertIsDisplayed()
        compose.onNodeWithContentDescription("录音频谱").assertIsDisplayed()
        compose.runOnIdle { armed.value = true }
        compose.onNodeWithText("松开取消").assertIsDisplayed()
        compose.onNodeWithText("松手发送，上滑取消").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w640dp-h320dp-land")
    fun `release hint stays on screen in a short window with large text`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        bar(recording = true, voiceMode = true)
                    }
                }
            }
        }
        compose.onNodeWithText("松手发送，上滑取消").assertIsDisplayed()
        compose.onNodeWithContentDescription("录音频谱").assertIsDisplayed()
    }

    @Test fun `model download shows percent size and a progress bar`() {
        compose.setContent {
            MaterialTheme {
                VoiceModelProgress(VoiceModelTransfer(10L * 1024 * 1024, 40L * 1024 * 1024))
            }
        }
        compose.onNodeWithText("正在下载语音模型").assertIsDisplayed()
        compose.onNodeWithText("10.0 MB / 40.0 MB").assertIsDisplayed()
        compose.onNodeWithText("25%").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音模型下载进度").assertIsDisplayed()
        compose.onNodeWithText("正在加载语音模型").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun bar(
        voiceMode: Boolean = false,
        recording: Boolean = false,
        cancelArmed: Boolean = false,
        stop: Boolean = false,
        sendEnabled: Boolean = false,
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        onEnterVoice: () -> Unit = {},
        onExitVoice: () -> Unit = {},
        onHoldTap: () -> Unit = {},
        onHoldStart: () -> Unit = {},
        onHoldMove: (Boolean) -> Unit = {},
        onHoldEnd: (Boolean) -> Unit = {},
    ) {
        VoiceComposerBar(
            voiceMode = voiceMode,
            recording = recording,
            cancelArmed = cancelArmed,
            level = 0.2f,
            transcript = "",
            enabled = true,
            micAvailable = true,
            stop = stop,
            stopEnabled = true,
            sendEnabled = sendEnabled,
            onAdd = {},
            onStop = onStop,
            onSend = onSend,
            onEnterVoice = onEnterVoice,
            onExitVoice = onExitVoice,
            onHoldTap = onHoldTap,
            onHoldStart = onHoldStart,
            onHoldMove = onHoldMove,
            onHoldEnd = onHoldEnd,
            textField = { Text("描述任务，或添加上下文") },
        )
    }
}
