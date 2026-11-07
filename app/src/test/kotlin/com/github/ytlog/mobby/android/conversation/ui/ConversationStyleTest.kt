package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun Color.warm() = red > blue

    // White, cool grey and a pale blue accent gave the page no character; this is the warm paper palette the user chose.
    @Test fun `the light palette is warm paper with readable ink and accent`() {
        val c = MobbyColors.Light.Conversation
        val light = MobbyColors.Light
        listOf(c.canvas, c.ink, c.toolInk, c.userBubble, light.page, light.card, light.drawer, light.muted, light.glassBase)
            .forEach { assertTrue("$it should lean warm", it.warm()) }
        assertEquals("replies and chrome use one ink", MobbyLightScheme.onSurface, c.ink)
        assertTrue(contrast(c.ink, c.canvas) >= 12f)
        assertTrue("secondary text stays readable", contrast(c.toolInk, c.canvas) >= 4.5f)
        assertTrue("accent text stays readable", contrast(MobbyLightScheme.primary, c.canvas) >= 4.5f)
        assertTrue("text on the accent stays readable", contrast(MobbyLightScheme.onPrimary, MobbyLightScheme.primary) >= 4.5f)
        assertTrue("warning stays readable", contrast(light.statusAccent, c.canvas) >= 4.5f)
    }

    @Test fun `warm near-white controls stay ordinary glass while the accent stays an accent`() {
        assertTrue(neutralLightTint(MobbyColors.Light.card))
        assertTrue(neutralLightTint(MobbyColors.Light.drawerControl))
        assertFalse(neutralLightTint(MobbyLightScheme.primary))
    }

    // Dark mode was not part of this choice.
    @Test fun `the dark palette keeps its accent`() {
        assertEquals(Color(0xFF80BAFF), MobbyDarkScheme.primary)
    }

    // At headline size the name towered over every control in the drawer.
    @Test fun `the drawer brand sits level with the controls around it`() {
        compose.setContent { MaterialTheme(colorScheme = MobbyLightScheme, typography = MobbyTypography) { DrawerBrand() } }
        compose.onNodeWithText(UiStrings.appName).assertExists()
        val brand = compose.onNodeWithTag("drawer-brand").getBoundsInRoot()
        assertTrue("brand height ${brand.bottom - brand.top}", brand.bottom - brand.top <= ToolbarControl)
    }

    @Test fun `process text sits clearly below reply text`() {
        assertTrue(ProcessTextSize.value <= MobbyTypography.bodyLarge.fontSize.value - 3f)
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
        assertEquals(0.dp, timelineGap(TimelineBlock.PROSE, TimelineBlock.ACTIONS))
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

    @Test fun `an unconfirmed queued submission puts the check action under its status`() {
        val message = "无法确认请求是否已提交，请稍后查询。"
        var checked = 0
        var restored = 0
        compose.setContent {
            MaterialTheme {
                TurnStatus(
                    turn.copy(phase = null, failure = message, pending = true, occupied = true, queued = true),
                    onCancelQueued = {},
                    onRestore = { restored++ },
                    onCheckPending = { checked++ },
                )
            }
        }
        val failure = compose.onNodeWithText(message).getBoundsInRoot()
        val action = compose.onNodeWithText("查询待确认请求")
        val actionBounds = action.getBoundsInRoot()
        assertTrue(failure.bottom <= actionBounds.top)
        assertEquals(failure.left.value, actionBounds.left.value, 1f)
        compose.onNodeWithText("恢复到输入框").assertDoesNotExist()
        compose.onNodeWithText(UiStrings.cancelQueuedMessage).assertDoesNotExist()
        action.performClick()
        compose.runOnIdle {
            assertEquals(1, checked)
            assertEquals(0, restored)
        }
    }

    @Test fun `a queued turn offers cancel and not restore`() {
        var cancelled = 0
        compose.setContent { MaterialTheme { TurnStatus(turn.copy(queued = true, phase = null), onCancelQueued = { cancelled++ }, onRestore = {}) } }
        compose.onNodeWithText("恢复到输入框").assertDoesNotExist()
        compose.onNodeWithText(UiStrings.cancelQueuedMessage).performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }
}
