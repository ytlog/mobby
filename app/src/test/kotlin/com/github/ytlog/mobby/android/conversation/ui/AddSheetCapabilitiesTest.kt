package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test fun `program development is absent when the host has no program platform`() {
        compose.setContent { Column { AddSheetCapabilities(programs = false) {} } }
        compose.onAllNodesWithText(AppStrings.programDevelopment).assertCountEquals(0)
    }
}
