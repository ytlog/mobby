package com.mobby.interaction.domain

import org.junit.Assert.*
import org.junit.Test

class ConversationSearchTest {
    @Test fun `user and assistant message targets never share the user key`() {
        val user = SearchHit(TurnId("one"), null, "same")
        val assistant = SearchHit(TurnId("one"), "user", "same")
        assertNotEquals(user.targetKey, assistant.targetKey)
        assertEquals("user:one", user.targetKey)
        assertEquals("message:one:user", assistant.targetKey)
    }
    @Test fun `search returns stable user and assistant targets without logs`() {
        val c = Conversation(ConversationId("conversation"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "CODEX"))
        val turns = listOf(Turn(TurnId("one"), "Find Needle", null, null,
            messages = listOf(Message("reply", "needle found")), diagnostics = listOf(Message("log", "needle secret")),
            steps = listOf(Step("step", "tool", "needle", "needle", null))))
        val matches = ConversationSearch.find(ConversationDetail(c, turns), "NEEDLE")
        assertEquals(2, matches.size)
        assertNull(matches[0].messageId)
        assertEquals("reply", matches[1].messageId)
        assertEquals(TurnId("one"), matches[1].turnId)
        assertTrue(ConversationSearch.find(ConversationDetail(c, turns), " ").isEmpty())
    }
}
