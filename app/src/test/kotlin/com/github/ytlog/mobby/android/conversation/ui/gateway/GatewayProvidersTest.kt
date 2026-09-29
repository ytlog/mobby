package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.ui.*

import com.github.ytlog.mobby.android.conversation.domain.AgentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayProvidersTest {
    @Test fun `presets expose the native base and custom is not implied by an unknown host`() {
        assertEquals(listOf("openrouter", "openai", "deepseek", "mimo", "kimi", "zhipu", "xai", "groq"), GatewayProviders.all.filter { it.responses != null }.map { it.id })
        assertEquals(listOf("openrouter", "anthropic", "deepseek", "mimo", "kimi", "zhipu"), GatewayProviders.all.filter { it.messages != null }.map { it.id })
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
            assertEquals(provider.candidates().responses, provider.candidates().forAgent(AgentId.OPEN_CODE))
            assertEquals(id, GatewayProviders.match(AgentId.CLAUDE_CODE, provider.messages!!))
        }
        assertEquals(null, GatewayProviders.find("openai")!!.messages)
        assertEquals("https://api.deepseek.com/anthropic/v1", GatewayProviders.find("deepseek")!!.messages)
        assertEquals("https://open.bigmodel.cn/api/v1", GatewayProviders.find("zhipu")!!.responses)
    }
}
