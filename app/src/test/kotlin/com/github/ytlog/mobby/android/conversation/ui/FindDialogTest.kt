package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FindDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `tapping search result returns original assistant message target`() {
        val conversation = Conversation(ConversationId("conversation"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val detail = ConversationDetail(conversation, listOf(Turn(TurnId("one"), "question", null, null,
            messages = listOf(Message("answer", "needle found")), diagnostics = listOf(Message("log", "needle diagnostic")))))
        var selected: SearchHit? = null
        compose.setContent { MaterialTheme { FindDialog(detail, onDismiss = {}, onSelect = { selected = it }) } }
        compose.onNode(hasSetTextAction()).performTextInput("needle")
        compose.onNodeWithText("needle diagnostic").assertDoesNotExist()
        compose.onNodeWithText("needle found").performClick()
        compose.runOnIdle { assertEquals("message:one:answer", selected?.targetKey) }
    }
}
