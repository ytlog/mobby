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

    private val DpRect.ratio get() = (right - left).value / (bottom - top).value
}
