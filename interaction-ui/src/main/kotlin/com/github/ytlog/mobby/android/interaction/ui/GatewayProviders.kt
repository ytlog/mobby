package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.AgentId

/** Official base URL for one native protocol. Selecting it only fills this address. */
internal data class GatewayProvider(val id: String, val label: String, val endpoint: String)

internal object GatewayProviders {
    const val CUSTOM = "custom"

    fun forAgent(agent: AgentId): List<GatewayProvider> = if (agent == AgentId.CLAUDE_CODE) CLAUDE else CODEX

    /** True when [endpoint] is a known base, ignoring a trailing slash or native API suffix. */
    fun matches(agent: AgentId, endpoint: String): Boolean = match(agent, endpoint) != CUSTOM

    fun match(agent: AgentId, endpoint: String): String {
        val normalized = normalize(endpoint)
        if (normalized.isEmpty()) return CUSTOM
        return forAgent(agent).firstOrNull { normalize(it.endpoint) == normalized }?.id ?: CUSTOM
    }

    private fun normalize(value: String): String =
        value.trim().trimEnd('/').replace(SUFFIX, "").lowercase()

    private val SUFFIX = Regex("(?i)/(?:chat/completions|responses|messages)$")
    private val CODEX = listOf(
        GatewayProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        GatewayProvider("openai", "OpenAI", "https://api.openai.com/v1"),
        GatewayProvider("xai", "xAI", "https://api.x.ai/v1"),
        GatewayProvider("groq", "Groq", "https://api.groq.com/openai/v1"),
    )
    private val CLAUDE = listOf(
        GatewayProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        GatewayProvider("anthropic", "Anthropic", "https://api.anthropic.com/v1"),
        GatewayProvider("deepseek", "DeepSeek", "https://api.deepseek.com/anthropic/v1"),
    )
}
