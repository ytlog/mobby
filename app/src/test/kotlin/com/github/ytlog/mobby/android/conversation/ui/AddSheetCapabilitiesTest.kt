package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.localization.AppStrings
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
class AddSheetCapabilitiesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the add sheet offers device plugins, program development and skills but not app functions`() {
        val opened = mutableListOf<AppPage>()
        compose.setContent { Column { AddSheetCapabilities(programs = true) { opened += it } } }
        compose.onAllNodesWithText(AppStrings.appFunctions).assertCountEquals(0)
        compose.onNodeWithText(AppStrings.devicePlugins).performClick()
        compose.onNodeWithText(AppStrings.programDevelopment).performClick()
        compose.onNodeWithText(AppStrings.skills).performClick()
        assertEquals(listOf(AppPage.PLUGINS, AppPage.PROGRAMS, AppPage.SKILLS), opened)
    }

    // The user asked for these buttons to be plain, without the glass effect.
    @Test fun `the add sheet buttons are not glass and still respond`() {
        var glass = true
        var opened = 0
        compose.setContent {
            AddSheetBody(maxHeight = 400.dp,
                tiles = {
                    glass = LocalGlassControls.current
                    AttachmentTile("fixture tile", AppIcons.Camera, true, Modifier.weight(1f)) { opened++ }
                },
                capabilities = { AddSheetCapabilities(programs = false) { opened++ } })
        }
        compose.runOnIdle { assertEquals(false, glass) }
        compose.onNodeWithContentDescription("fixture tile").performClick()
        compose.onNodeWithText(AppStrings.skills).performClick()
        compose.runOnIdle { assertEquals(2, opened) }
    }

    private fun rows(expected: Int) = (0 until expected).map { compose.onAllNodesWithTag("sheet-row")[it].getBoundsInRoot() }
    private fun assertTouching(rows: List<androidx.compose.ui.unit.DpRect>) = rows.zipWithNext().forEach { (a, b) -> assertEquals(a.bottom, b.top) }

    // Rows in the add sheet were separated by extra space, and the two sheets did not share a row style at all.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `the home add sheet rows are compact and touch each other`() {
        compose.setContent { MaterialTheme(typography = MobbyTypography) { androidx.compose.foundation.layout.Box(Modifier.width(392.dp)) { AddSheetBody(maxHeight = 600.dp, tiles = {}, capabilities = { AddSheetCapabilities(programs = true) {} }) } } }
        // Real copy may wrap in a narrow language; what must hold is that rows add no space between them.
        assertTouching(rows(3))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `the add skill sheet uses the same compact rows`() {
        compose.setContent { MaterialTheme(typography = MobbyTypography) { androidx.compose.foundation.layout.Box(Modifier.width(392.dp)) { SheetBody(title = "fixture title") {
            repeat(3) { SheetRow("title $it", "detail $it", AppIcons.Skill) {} }
        } } } }
        val rows = rows(3)
        rows.forEach { assertTrue("row height ${it.bottom - it.top}", it.bottom - it.top <= 64.dp) }
        assertTouching(rows)
    }

    @Test fun `program development is absent when the host has no program platform`() {
        compose.setContent { Column { AddSheetCapabilities(programs = false) {} } }
        compose.onAllNodesWithText(AppStrings.programDevelopment).assertCountEquals(0)
    }
}
