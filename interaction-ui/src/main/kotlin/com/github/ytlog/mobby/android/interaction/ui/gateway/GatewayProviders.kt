package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.domain.AgentId
import com.github.ytlog.mobby.android.interaction.domain.gateway.GatewayAddresses

/** Preset URLs are protocol candidates, never a claim that a particular Agent works. */
internal data class GatewayProvider(val id: String, val label: String, val responses: String? = null, val messages: String? = null) {
    fun candidates(): GatewayAddresses {
        val primary = responses ?: requireNotNull(messages)
        return GatewayAddresses(primary, messages ?: primary)
    }
    fun listedAddress(agent: AgentId): String? = if (agent == AgentId.CLAUDE_CODE) messages else responses
}

internal object GatewayProviders {
    const val CUSTOM = "custom"

    private fun provider(id: String, label: String, responses: String? = null, messages: String? = null) =
        GatewayProvider(id, label, responses, messages)

    val all = listOf(
        provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1"),
        provider("openai", "OpenAI", "https://api.openai.com/v1"),
        provider("anthropic", "Anthropic", messages = "https://api.anthropic.com/v1"),
        provider("deepseek", "DeepSeek", "https://api.deepseek.com", "https://api.deepseek.com/anthropic/v1"),
        provider("mimo", "小米 MiMo", "https://api.xiaomimimo.com/v1", "https://api.xiaomimimo.com/anthropic/v1"),
        provider("kimi", "Kimi", "https://api.moonshot.ai/v1", "https://api.moonshot.ai/anthropic/v1"),
        provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/v1", "https://open.bigmodel.cn/api/anthropic/v1"),
        provider("xai", "xAI", "https://api.x.ai/v1"),
        provider("groq", "Groq", "https://api.groq.com/openai/v1"),
    )
    fun find(id: String): GatewayProvider? = all.firstOrNull { it.id == id }

    /** True when [endpoint] is a known base, ignoring a trailing slash or native API suffix. */
    fun matches(agent: AgentId, endpoint: String): Boolean = match(agent, endpoint) != CUSTOM

    fun match(agent: AgentId, endpoint: String): String {
        val normalized = normalize(endpoint)
        if (normalized.isEmpty()) return CUSTOM
        return all.firstOrNull { normalize(it.listedAddress(agent).orEmpty()) == normalized }?.id ?: CUSTOM
    }

    private fun normalize(value: String): String =
        value.trim().trimEnd('/').replace(SUFFIX, "").lowercase()

    private val SUFFIX = Regex("(?i)/(?:chat/completions|responses|messages)$")
}
