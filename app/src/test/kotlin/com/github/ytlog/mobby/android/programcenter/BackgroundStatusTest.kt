package com.github.ytlog.mobby.android.programcenter

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
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
class BackgroundStatusTest {
    @get:Rule val compose = createComposeRule()

    /** On the tablet the system deferred scheduled runs for hours while mobby was not exempt from battery optimization. */
    @Test fun `a restricted app explains the delay and offers the system request`() {
        var requests = 0
        compose.setContent { MaterialTheme { Column { BackgroundStatus(allowed = false) { requests++ } } } }
        compose.onNodeWithText(AppStrings.backgroundRestricted).assertExists()
        compose.onNodeWithText(AppStrings.allowBackground).performClick()
        assertEquals(1, requests)
    }

    @Test fun `an exempt app shows no request`() {
        compose.setContent { MaterialTheme { Column { BackgroundStatus(allowed = true) {} } } }
        compose.onNodeWithText(AppStrings.backgroundAllowed).assertExists()
        compose.onNodeWithText(AppStrings.allowBackground).assertDoesNotExist()
    }
}
