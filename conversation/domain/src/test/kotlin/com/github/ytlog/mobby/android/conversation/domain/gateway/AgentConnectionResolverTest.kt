package com.github.ytlog.mobby.android.conversation.domain.gateway

import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.NextTurnConfig
import org.junit.Assert.*
import org.junit.Test

class AgentConnectionResolverTest {
    private val api = GatewayProfile(AgentId.CODEX, "existing-api", 4, "https://example.invalid/v1", "api-model", "RESPONSES", true)
    private val native = GatewayProfile(AgentId.CODEX, "native:CODEX", 2, "", "account-model", "RESPONSES", true,
        source = ConnectionSource.NATIVE, connectionLabel = "ChatGPT")

    @Test fun `automatic prefers native only for its own agent and preserves API fallback`() {
        val pi = api.copy(agent = AgentId.PI)
        val profiles = AgentConnectionResolver.available(listOf(api, pi), listOf(native), emptyList())
        assertEquals(setOf(native, pi), profiles.toSet())
        assertEquals(setOf(api, pi), AgentConnectionResolver.available(listOf(api, pi), emptyList(), emptyList()).toSet())
    }
    @Test fun `explicit source is strict and selecting API never deletes native login`() {
        val settings = listOf(AgentConnectionPreference(AgentId.CODEX, ConnectionMode.API_ONLY, api.id))
        assertEquals(listOf(api), AgentConnectionResolver.available(listOf(api), listOf(native), settings))
        assertEquals(listOf(native), AgentConnectionResolver.available(listOf(api), listOf(native),
            listOf(AgentConnectionPreference(AgentId.CODEX, ConnectionMode.NATIVE_ONLY))))
        assertTrue(AgentConnectionResolver.available(listOf(api), emptyList(),
            listOf(AgentConnectionPreference(AgentId.CODEX, ConnectionMode.NATIVE_ONLY))).isEmpty())
    }
    @Test fun `source changes keep the selected agent workspace and choose a valid model`() {
        val config = NextTurnConfig(AgentId.CODEX, api.model, null, "existing-workspace", api.id, api.version)
        val changed = ConversationGatewayResolver.repair(config, listOf(native, api.copy(agent = AgentId.PI)), null)!!
        assertEquals(AgentId.CODEX, changed.agent)
        assertEquals("existing-workspace", changed.workspace)
        assertEquals(native.id, changed.gatewayProfile)
        assertEquals(native.model, changed.model)
    }
    @Test fun `unavailable agent in an existing conversation is not silently replaced`() {
        val config = NextTurnConfig(AgentId.CODEX, api.model, null, "existing-workspace", api.id, api.version)
        assertNull(ConversationGatewayResolver.repair(config, listOf(api.copy(agent = AgentId.PI)), null))
    }
}
