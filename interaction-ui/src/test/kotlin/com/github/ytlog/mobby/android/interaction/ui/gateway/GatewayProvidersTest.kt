package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.ui.*

import com.github.ytlog.mobby.android.interaction.domain.AgentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayProvidersTest {
    @Test fun `presets expose the native base and custom is not implied by an unknown host`() {
        assertEquals(listOf("openrouter", "openai", "deepseek", "mimo", "kimi", "zhipu", "xai", "groq"), GatewayProviders.forAgent(AgentId.CODEX).map { it.id })
        assertEquals(GatewayProviders.forAgent(AgentId.CODEX), GatewayProviders.forAgent(AgentId.OPEN_CODE))
        assertEquals(listOf("openrouter", "anthropic", "deepseek", "mimo", "kimi", "zhipu"), GatewayProviders.forAgent(AgentId.CLAUDE_CODE).map { it.id })
        assertEquals("openai", GatewayProviders.match(AgentId.CODEX, "https://api.openai.com/v1/responses"))
        assertEquals("groq", GatewayProviders.match(AgentId.CODEX, "https://api.groq.com/openai/v1/"))
        assertEquals("deepseek", GatewayProviders.match(AgentId.CLAUDE_CODE, "https://api.deepseek.com/anthropic/v1/messages"))
        assertEquals(GatewayProviders.CUSTOM, GatewayProviders.match(AgentId.CODEX, "https://api.deepseek.com/anthropic/v1"))
        assertEquals(GatewayProviders.CUSTOM, GatewayProviders.match(AgentId.CLAUDE_CODE, "https://gateway.invalid/v1"))
        assertFalse(GatewayProviders.matches(AgentId.CODEX, "https://generativelanguage.googleapis.com/v1beta/openai"))
    }

    @Test fun `multi agent providers use each native endpoint`() {
        for (id in listOf("openrouter", "deepseek", "mimo", "kimi", "zhipu")) {
            val provider = GatewayProviders.find(id)!!
            assertEquals(AgentId.values().toSet(), provider.agents)
            assertEquals(provider.endpoint(AgentId.CODEX), provider.endpoint(AgentId.OPEN_CODE))
            assertEquals(id, GatewayProviders.match(AgentId.CLAUDE_CODE, provider.endpoint(AgentId.CLAUDE_CODE)!!))
        }
        assertEquals(setOf(AgentId.CODEX, AgentId.OPEN_CODE), GatewayProviders.find("openai")!!.agents)
        assertEquals("https://api.deepseek.com/anthropic/v1", GatewayProviders.find("deepseek")!!.endpoint(AgentId.CLAUDE_CODE))
        assertEquals("https://open.bigmodel.cn/api/v1", GatewayProviders.find("zhipu")!!.endpoint(AgentId.CODEX))
    }
}
