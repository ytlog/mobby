package com.github.ytlog.mobby.android.programcenter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
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
        compose.onNodeWithText(UiStrings.programIdeaSplashAsk).performClick()
        compose.onNodeWithText(UiStrings.programIdeaExpensesAsk).performClick()
        compose.onNodeWithText(UiStrings.programIdeaReminderAsk).performClick()
        compose.onNodeWithText(UiStrings.programIdeaWatchAsk).performClick()
        assertEquals(listOf(UiStrings.programIdeaSplashRequest, UiStrings.programIdeaExpensesRequest, UiStrings.programIdeaReminderRequest, UiStrings.programIdeaWatchRequest), picked)
    }

    /** On the phone the ideas were rows like installed programs, each with a subtitle and a chevron. */
    @Test fun `ideas read as requests for the AI as message bubbles rather than as programs`() {
        compose.setContent { MaterialTheme { ProgramIdeas {} } }
        for (idea in listOf(UiStrings.programIdeaSplashAsk, UiStrings.programIdeaExpensesAsk, UiStrings.programIdeaReminderAsk, UiStrings.programIdeaWatchAsk))
            compose.onNodeWithText(idea).assertHasClickAction()
        compose.onAllNodesWithTag("program-idea-bubble").assertCountEquals(4)
        compose.onAllNodesWithText("记一笔支出，按月汇总，导出 CSV").assertCountEquals(0)
    }

    @Test fun `the screen banner says why programs stopped and opens the setting`() {
        var opened = 0
        compose.setContent { MaterialTheme { ScreenAccessStatus(true) { opened++ } } }
        compose.onNodeWithText(UiStrings.screenAccessOff).assertExists()
        compose.onNodeWithText(UiStrings.screenAccessOffHint).assertExists()
        compose.onNodeWithText(UiStrings.turnOnShort).performClick()
        assertEquals(1, opened)
    }

    @Test fun `an idea fills an empty draft and is added after text already typed`() {
        assertEquals("写一个记账程序", withIdea("", "写一个记账程序"))
        assertEquals("先看看\n写一个记账程序", withIdea("先看看  ", "写一个记账程序"))
    }
}
