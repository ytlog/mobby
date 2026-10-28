package com.github.ytlog.mobby.android.conversation.data

import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredConversationTest {
    /** "让 AI 修改此程序" lost its attached program once the new conversation was saved and loaded. */
    @Test fun `attached programs survive storage alongside device plugins`() {
        val source = Conversation(ConversationId("c"), NextTurnConfig(AgentId.PI, "model", null, "default", "PI"))
        val created = ConversationRules.createProgramConversation(source, ConversationId("n"), "header-watch", "Header Watch")
        assertEquals(setOf("plugin:device:programs", "plugin:program:header-watch"), StoredConversation.from(created).domain().draft.capabilities)
    }
}
