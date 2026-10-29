package com.github.ytlog.mobby.android.programcenter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.ytlog.mobby.android.conversation.ui.UiStrings
import com.github.ytlog.mobby.android.conversation.ui.withIdea
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramIdeasTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `each idea hands its request to the conversation`() {
        val picked = mutableListOf<String>()
        compose.setContent { MaterialTheme { ProgramIdeas { picked += it } } }
        compose.onNodeWithText(UiStrings.programIdeaExpenses).performClick()
        compose.onNodeWithText(UiStrings.programIdeaReminder).performClick()
        compose.onNodeWithText(UiStrings.programIdeaWatch).performClick()
        assertEquals(listOf(UiStrings.programIdeaExpensesRequest, UiStrings.programIdeaReminderRequest, UiStrings.programIdeaWatchRequest), picked)
    }

    @Test fun `an idea fills an empty draft and is added after text already typed`() {
        assertEquals("写一个记账程序", withIdea("", "写一个记账程序"))
        assertEquals("先看看\n写一个记账程序", withIdea("先看看  ", "写一个记账程序"))
    }
}
