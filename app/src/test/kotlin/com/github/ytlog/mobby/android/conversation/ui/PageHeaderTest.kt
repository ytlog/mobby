package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `title and back control share one horizontal center line`() {
        compose.setContent { MaterialTheme { PageHeader("设置", {}) } }
        val back = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
        assertEquals(back.center.y, title.center.y, 1f)
    }
}
