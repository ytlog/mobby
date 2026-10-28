package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramDrawerSectionTest {
    @get:Rule val compose = createComposeRule()
    private val shortcuts = listOf(ProgramShortcut("header-watch", "Header Watch", opensPage = true),
        ProgramShortcut("price", "Price Watch", opensPage = false, note = "需要填写配置"))

    @Test fun `programs open their page or their details, and all programs opens program development`() {
        val actions = mutableListOf<String>()
        compose.setContent { MaterialTheme { LazyColumn { programSection(shortcuts, "", { actions += "page:$it" }, { actions += "details:$it" }) { actions += "all" } } } }
        compose.onNodeWithText(UiStrings.programs).assertExists()
        compose.onNodeWithText("Header Watch").performClick()
        compose.onNodeWithText("Price Watch · 需要填写配置").performClick()
        compose.onNodeWithText(UiStrings.allPrograms).performClick()
        assertEquals(listOf("page:header-watch", "details:price", "all"), actions)
    }

    @Test fun `the section is hidden without programs or when the search matches none`() {
        compose.setContent { MaterialTheme { LazyColumn {
            programSection(emptyList(), "", {}, {}) {}
            programSection(shortcuts, "zzz", {}, {}) {}
        } } }
        compose.onNodeWithText(UiStrings.programs).assertDoesNotExist()
        compose.onNodeWithText(UiStrings.allPrograms).assertDoesNotExist()
    }
}
