package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationStyleTest {
    @get:Rule val compose = createComposeRule()
    private val turn = Turn(TurnId("t"), "fixture", null, ExecutionPhase.CANCELLED)

    // Material's Latin tracking (0.25-0.5sp) visibly loosens Chinese body text.
    @Test fun `text styles carry no extra tracking`() {
        with(MobbyTypography) {
            listOf(bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall, titleMedium, titleSmall)
                .forEach { assertEquals(0f, it.letterSpacing.value, 0f) }
        }
    }

    @Test fun `reply text keeps a comfortable reading measure`() {
        assertEquals(16.sp, MobbyTypography.bodyLarge.fontSize)
        assertTrue(MobbyTypography.bodyLarge.lineHeight.value >= 25f)
    }

    // Unset scheme fields fall back to Material's baseline purple, as the selected context chip showed.
    @Test fun `color schemes do not inherit the baseline purple`() {
        listOf(MobbyLightScheme to lightColorScheme(), MobbyDarkScheme to darkColorScheme()).forEach { (ours, baseline) ->
            listOf<Pair<Color, Color>>(
                ours.primaryContainer to baseline.primaryContainer,
                ours.secondaryContainer to baseline.secondaryContainer,
                ours.tertiary to baseline.tertiary,
                ours.tertiaryContainer to baseline.tertiaryContainer,
                ours.surfaceContainerLow to baseline.surfaceContainerLow,
                ours.surfaceContainer to baseline.surfaceContainer,
                ours.surfaceContainerHigh to baseline.surfaceContainerHigh,
                ours.surfaceContainerHighest to baseline.surfaceContainerHighest,
                ours.surfaceTint to baseline.surfaceTint,
            ).forEach { (mine, default) -> assertNotEquals(default, mine) }
        }
    }

    // A white card on the white light canvas was invisible, so code blocks had no edge at all.
    @Test fun `cards are distinguishable from the conversation canvas in both themes`() {
        val seen = mutableListOf<Pair<Color, Color>>()
        compose.setContent {
            listOf(MobbyLightScheme, MobbyDarkScheme).forEach { scheme ->
                MaterialTheme(colorScheme = scheme) { seen += conversationCardColor() to conversationCanvas() }
            }
        }
        compose.waitForIdle()
        assertEquals(2, seen.size)
        seen.forEach { (card, canvas) -> assertNotEquals(canvas, card) }
    }

    @Test fun `process rows read as one group while new turns get room`() {
        val process = timelineGap(TimelineBlock.PROCESS, TimelineBlock.PROCESS)
        val around = timelineGap(TimelineBlock.PROSE, TimelineBlock.PROCESS)
        val newTurn = timelineGap(TimelineBlock.PROSE, TimelineBlock.USER)
        assertEquals(0.dp, process)
        assertTrue(process < around)
        assertTrue(around < newTurn)
        assertEquals(0.dp, timelineGap(null, TimelineBlock.USER))
    }

    @Test fun `a stopped turn is one short row and still offers restore`() {
        var restored = 0
        compose.setContent { MaterialTheme { TurnStatus(turn, onCancelQueued = {}, onRestore = { restored++ }) } }
        compose.onNodeWithText("已停止").assertExists()
        val height = compose.onNodeWithTag("turn-status").getBoundsInRoot().let { it.bottom - it.top }
        assertTrue("height was $height", height <= 56.dp)
        compose.onNodeWithText("恢复到输入框").performClick()
        compose.runOnIdle { assertEquals(1, restored) }
    }

    @Test fun `a failure keeps its whole message and its restore action`() {
        val message = "网关返回错误：请求被拒绝，请检查配置后重试。".repeat(4)
        var restored = 0
        compose.setContent { MaterialTheme { TurnStatus(turn.copy(failure = message, phase = ExecutionPhase.FAILED), onCancelQueued = {}, onRestore = { restored++ }) } }
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("恢复到输入框").performClick()
        compose.runOnIdle { assertEquals(1, restored) }
    }

    @Test fun `a queued turn offers cancel and not restore`() {
        var cancelled = 0
        compose.setContent { MaterialTheme { TurnStatus(turn.copy(queued = true, phase = null), onCancelQueued = { cancelled++ }, onRestore = {}) } }
        compose.onNodeWithText("恢复到输入框").assertDoesNotExist()
        compose.onNodeWithText(UiStrings.cancelQueuedMessage).performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }
}
