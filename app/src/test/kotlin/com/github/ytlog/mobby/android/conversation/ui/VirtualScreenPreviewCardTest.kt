package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.runtime.api.device.ManualScreenInput
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenTouchPhase
import java.lang.reflect.Proxy
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
class VirtualScreenPreviewCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyDisplayKeepsItsPlaceholderUntilAnAppIsObserved() {
        val blankFrame = ImageBitmap(72, 128)
        val hasObservedApp = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                VirtualScreenPreviewCard(blankFrame, null, true, {}, Modifier.width(184.dp),
                    hasObservedApp = hasObservedApp.value)
            }
        }
        val preview = compose.onNodeWithContentDescription(UiStrings.virtualScreenPreview)
        compose.onNodeWithText(UiStrings.virtualScreenWaiting).assertIsDisplayed()
        preview.assertDoesNotExist()
        compose.runOnIdle { hasObservedApp.value = true }
        preview.assertIsDisplayed()
        compose.onNodeWithText(UiStrings.virtualScreenWaiting).assertDoesNotExist()
    }

    @Test fun frameKeepsItsRatioAsContentChangesAndCanBeCollapsed() {
        val frame = mutableStateOf(ImageBitmap(72, 128))
        val expanded = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                VirtualScreenPreviewCard(frame.value, null, expanded.value, { expanded.value = !expanded.value },
                    Modifier.width(184.dp))
            }
        }
        val image = compose.onNodeWithContentDescription(UiStrings.virtualScreenPreview)
        val portrait = image.getBoundsInRoot()
        assertEquals(72f / 128f, portrait.ratio, 0.01f)
        assertTrue(portrait.right - portrait.left >= 160.dp)
        compose.runOnIdle { frame.value = ImageBitmap(128, 72) }
        val landscape = image.getBoundsInRoot()
        assertEquals(128f / 72f, landscape.ratio, 0.02f)
        compose.onNodeWithContentDescription(UiStrings.hideVirtualScreen).performClick()
        image.assertDoesNotExist()
        compose.onNodeWithContentDescription(UiStrings.showVirtualScreen).performClick()
        image.assertIsDisplayed()
    }

    @Test fun shortWindowFitsTheWholeFrameWithoutDistortingIt() {
        compose.setContent {
            MaterialTheme {
                VirtualScreenPreviewCard(ImageBitmap(72, 128), null, true, {},
                    Modifier.width(184.dp).heightIn(max = 180.dp))
            }
        }
        val frame = compose.onNodeWithContentDescription(UiStrings.virtualScreenPreview).assertIsDisplayed().getBoundsInRoot()
        assertEquals(72f / 128f, frame.ratio, 0.02f)
        compose.onNodeWithContentDescription(UiStrings.hideVirtualScreen).assertIsDisplayed()
    }

    @Test fun takeoverStaysInTheHeaderWhenThePreviewIsCollapsed() {
        var tookOver = false
        compose.setContent { MaterialTheme {
            VirtualScreenPreviewCard(null, null, false, {}, Modifier.width(184.dp), onTakeOver = { tookOver = true })
        } }
        val takeover = compose.onNodeWithContentDescription(UiStrings.virtualScreenTakeOver).assertIsDisplayed()
        val fold = compose.onNodeWithContentDescription(UiStrings.showVirtualScreen).assertIsDisplayed()
        val label = compose.onNodeWithText(UiStrings.virtualScreenShortTitle).assertIsDisplayed().getBoundsInRoot()
        val button = takeover.getBoundsInRoot()
        assertTrue(button.left >= label.right)
        assertTrue(button.top < fold.getBoundsInRoot().bottom && label.top < button.bottom)
        takeover.performClick()
        compose.runOnIdle { assertTrue(tookOver) }
    }

    @Test fun openingTheLocalEditorPreparesTheVirtualTargetBeforeTyping() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val controller = Proxy.newProxyInstance(ScreenControlPort::class.java.classLoader,
            arrayOf(ScreenControlPort::class.java)) { _, method, arguments ->
            calls += method.name to arguments.orEmpty().dropLast(1)
            if (method.name == "prepareManualText") "Probe code" else Unit
        } as ScreenControlPort
        compose.setContent { MaterialTheme {
            ManualVirtualScreenDialog(controller, "handoff-fixture", ImageBitmap(72, 128), null, onMinimize = {})
        } }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.virtualScreenManualInput).performClick()
        compose.runOnIdle { assertEquals(listOf("prepareManualText" to listOf("handoff-fixture")), calls) }
        compose.onAllNodes(isDialog()).assertCountEquals(2)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performTextInput("123456")
        compose.onNodeWithText(UiStrings.virtualScreenManualFill).performClick()
        compose.runOnIdle { assertEquals("manualInput" to listOf("handoff-fixture", ManualScreenInput.Text("123456")), calls.last()) }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }

    @Test fun manualDialogSendsAStrokeToItsDisplayAndMinimizingDoesNotReturnControl() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val controller = Proxy.newProxyInstance(ScreenControlPort::class.java.classLoader,
            arrayOf(ScreenControlPort::class.java)) { _, method, arguments ->
            calls += method.name to arguments.orEmpty().dropLast(1)
            Unit
        } as ScreenControlPort
        var minimized = false
        val bitmap = ImageBitmap(72, 128)
        compose.setContent { MaterialTheme {
            ManualVirtualScreenDialog(controller, "handoff-fixture", bitmap, null, onMinimize = { minimized = true })
        } }
        val screen = compose.onNodeWithContentDescription(UiStrings.virtualScreenPreview)
        screen.performTouchInput { down(centerLeft) }
        compose.runOnIdle {
            assertEquals(ScreenTouchPhase.DOWN, (calls.single().second[1] as ManualScreenInput.Touch).phase)
        }
        screen.performTouchInput { moveTo(centerRight, delayMillis = 400) }
        compose.runOnIdle {
            assertEquals(ScreenTouchPhase.MOVE, (calls.last().second[1] as ManualScreenInput.Touch).phase)
        }
        screen.performTouchInput { up() }
        compose.runOnIdle {
            val touches = calls.map { (method, args) ->
                assertEquals("manualInput", method)
                assertEquals("handoff-fixture", args[0])
                args[1] as ManualScreenInput.Touch
            }
            assertEquals(listOf(ScreenTouchPhase.DOWN, ScreenTouchPhase.MOVE, ScreenTouchPhase.UP), touches.map { it.phase })
            assertTrue(touches.first().x < touches.last().x)
            assertEquals(touches[1].x, touches.last().x)
        }
        screen.performTouchInput { down(center) }
        screen.performTouchInput { cancel() }
        compose.runOnIdle { assertEquals(ManualScreenInput.CancelTouch, calls.last().second[1]) }
        compose.onNodeWithText(UiStrings.virtualScreenMinimizeManual).performClick()
        compose.runOnIdle { assertTrue(minimized); assertEquals(5, calls.size) }
        compose.onNodeWithText(UiStrings.virtualScreenHandBack).performClick()
        compose.runOnIdle { assertEquals("handBack" to listOf("handoff-fixture"), calls.last()) }
    }

    @Test fun aFailedTextPreparationExplainsWhyNoEditorOpened() {
        val controller = Proxy.newProxyInstance(ScreenControlPort::class.java.classLoader,
            arrayOf(ScreenControlPort::class.java)) { _, method, _ ->
            if (method.name == "prepareManualText") throw IllegalStateException(UiStrings.virtualScreenManualSelectField) else Unit
        } as ScreenControlPort
        compose.setContent { MaterialTheme {
            ManualVirtualScreenDialog(controller, "handoff-fixture", ImageBitmap(72, 128), null, onMinimize = {})
        } }
        compose.onNodeWithText(UiStrings.virtualScreenManualInput).performClick()
        compose.onNodeWithText(UiStrings.virtualScreenManualSelectField).assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }

    private val DpRect.ratio get() = (right - left).value / (bottom - top).value
}
