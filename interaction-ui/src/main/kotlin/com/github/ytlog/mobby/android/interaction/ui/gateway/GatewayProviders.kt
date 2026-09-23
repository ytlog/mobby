package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.domain.AgentId

/** Only advertise native protocol endpoints documented by the provider. */
internal data class GatewayProvider(val id: String, val label: String, val endpoints: Map<AgentId, String>) {
    fun endpoint(agent: AgentId): String? = endpoints[agent]
    val agents: Set<AgentId> get() = endpoints.keys
}

internal object GatewayProviders {
    const val CUSTOM = "custom"

    private val RESPONSES = listOf(AgentId.CODEX, AgentId.OPEN_CODE)
    private fun provider(id: String, label: String, responses: String? = null, messages: String? = null) =
        GatewayProvider(id, label, buildMap {
            if (responses != null) RESPONSES.forEach { put(it, responses) }
            if (messages != null) put(AgentId.CLAUDE_CODE, messages)
        })

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
    fun forAgent(agent: AgentId): List<GatewayProvider> = all.filter { agent in it.agents }
    fun find(id: String): GatewayProvider? = all.firstOrNull { it.id == id }

    /** True when [endpoint] is a known base, ignoring a trailing slash or native API suffix. */
    fun matches(agent: AgentId, endpoint: String): Boolean = match(agent, endpoint) != CUSTOM

    fun match(agent: AgentId, endpoint: String): String {
        val normalized = normalize(endpoint)
        if (normalized.isEmpty()) return CUSTOM
        return forAgent(agent).firstOrNull { normalize(it.endpoint(agent).orEmpty()) == normalized }?.id ?: CUSTOM
    }

    private fun normalize(value: String): String =
        value.trim().trimEnd('/').replace(SUFFIX, "").lowercase()

    private val SUFFIX = Regex("(?i)/(?:chat/completions|responses|messages)$")
}
