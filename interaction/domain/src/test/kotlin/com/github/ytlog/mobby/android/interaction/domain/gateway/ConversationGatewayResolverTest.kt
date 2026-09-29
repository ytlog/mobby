package com.github.ytlog.mobby.android.interaction.domain.gateway

import com.github.ytlog.mobby.android.interaction.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationGatewayResolverTest {
    private val main = GatewayProfile(AgentId.CODEX, "main", 3, "https://example.test/v1", "base", "RESPONSES", true,
        listOf(GatewayModel("base", "Base"), GatewayModel("chosen", "Chosen")))
    private val selected = GatewayDefault(AgentId.CODEX, main.id, main.version)

    @Test fun `Pi is preferred without a user default and an explicit later choice is retained`() {
        val pi = main.copy(agent = AgentId.PI)
        assertEquals(AgentId.PI, ConversationGatewayResolver.preferred(listOf(main, pi), null)?.agent)
        assertEquals(AgentId.CODEX, ConversationGatewayResolver.preferred(listOf(main, pi), selected)?.agent)
        val existing = NextTurnConfig(AgentId.CODEX, "base", null, "default", main.id, main.version)
        assertNull(ConversationGatewayResolver.repair(existing, listOf(main, pi), GatewayDefault(AgentId.PI, pi.id, pi.version)))
    }

    @Test fun `new conversation remembers the last model on the selected gateway`() {
        val recent = Conversation(ConversationId("recent"), NextTurnConfig(AgentId.CODEX, "chosen", "high", "default", main.id, 2), updatedAt = 20)
        val config = ConversationGatewayResolver.newConversation(AgentId.CODEX, recent, listOf(ConversationSummary(recent)), listOf(main), selected)
        assertEquals("chosen", config?.model)
        assertEquals("high", config?.reasoning)
        assertEquals(3L, config?.gatewayVersion)
    }

    @Test fun `last model can follow the user to a gateway that offers it`() {
        val recent = Conversation(ConversationId("recent"), NextTurnConfig(AgentId.CODEX, "chosen", "high", "default", "old", 1), updatedAt = 20)
        val config = ConversationGatewayResolver.newConversation(AgentId.CODEX, recent, listOf(ConversationSummary(recent)), listOf(main), selected)
        assertEquals(main.id, config?.gatewayProfile)
        assertEquals("chosen", config?.model)
        assertNull(config?.reasoning)
    }

    @Test fun `missing last model falls back to the selected gateway default`() {
        val recent = Conversation(ConversationId("recent"), NextTurnConfig(AgentId.CODEX, "removed", "high", "default", main.id, 2), updatedAt = 20)
        val config = ConversationGatewayResolver.newConversation(AgentId.CODEX, recent, listOf(ConversationSummary(recent)), listOf(main), selected)
        assertEquals("base", config?.model)
        assertNull(config?.reasoning)
    }
}
