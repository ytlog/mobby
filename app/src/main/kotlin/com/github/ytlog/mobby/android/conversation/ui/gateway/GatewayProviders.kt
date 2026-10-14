package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.localization.AppLanguage
import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayAddresses
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayProfile
import com.github.ytlog.mobby.android.conversation.ui.LanguagePreferences

internal enum class GatewaySite { SHARED, CHINA, INTERNATIONAL }

/** Preset URLs are protocol candidates, never a claim that a particular Agent works. */
internal data class GatewayProvider(val id: String, val label: String, val responses: String? = null, val messages: String? = null, val site: GatewaySite = GatewaySite.SHARED) {
    val domestic get() = id in setOf("bailian", "volcengine", "deepseek", "mimo", "kimi", "zhipu")
    fun candidates(): GatewayAddresses {
        val primary = responses ?: requireNotNull(messages)
        return GatewayAddresses(primary, messages ?: primary)
    }
    fun listedAddress(agent: AgentId): String? = if (agent == AgentId.CLAUDE_CODE) messages else responses
}

internal object GatewayProviders {
    const val CUSTOM = "custom"
    const val LOCAL = "local"

    private fun provider(id: String, label: String, responses: String? = null, messages: String? = null, site: GatewaySite = GatewaySite.SHARED) =
        GatewayProvider(id, label, responses, messages, site)

    private val variants get() = listOf(
        provider("openrouter", AppStrings.openRouterName, "https://openrouter.ai/api/v1", "https://openrouter.ai/api/v1"),
        provider("openai", AppStrings.openAiName, "https://api.openai.com/v1"),
        provider("anthropic", AppStrings.anthropicName, messages = "https://api.anthropic.com/v1"),
        provider("deepseek", AppStrings.deepSeekName, "https://api.deepseek.com", "https://api.deepseek.com/anthropic/v1"),
        provider("mimo", AppStrings.xiaomiMimo, "https://api.xiaomimimo.com/v1", "https://api.xiaomimimo.com/anthropic/v1"),
        provider("kimi", AppStrings.kimiName, "https://api.moonshot.ai/v1", "https://api.moonshot.ai/anthropic/v1", GatewaySite.INTERNATIONAL),
        provider("kimi", AppStrings.kimiName, "https://api.moonshot.cn/v1", "https://api.moonshot.cn/anthropic/v1", GatewaySite.CHINA),
        provider("zhipu", AppStrings.zhipuGlm, "https://open.bigmodel.cn/api/v1", "https://open.bigmodel.cn/api/anthropic/v1", GatewaySite.CHINA),
        provider("zhipu", AppStrings.zaiName, messages = "https://api.z.ai/api/anthropic/v1", site = GatewaySite.INTERNATIONAL),
        provider("xai", AppStrings.xaiName, "https://api.x.ai/v1"),
        provider("groq", AppStrings.groqName, "https://api.groq.com/openai/v1"),
        provider("bailian", AppStrings.aliyunBailianName, "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/apps/anthropic/v1", GatewaySite.CHINA),
        provider("bailian", AppStrings.aliyunBailianName, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "https://dashscope-intl.aliyuncs.com/apps/anthropic/v1", GatewaySite.INTERNATIONAL),
        provider("volcengine", AppStrings.volcengineArkName, "https://ark.cn-beijing.volces.com/api/v3", "https://ark.cn-beijing.volces.com/api/compatible/v1", GatewaySite.CHINA),
        provider("volcengine", AppStrings.bytePlusModelArkName, "https://ark.ap-southeast.bytepluses.com/api/v3", "https://ark.ap-southeast.bytepluses.com/api/compatible/v1", GatewaySite.INTERNATIONAL),
        provider("requesty", AppStrings.requestyName, "https://router.requesty.ai/v1", "https://router.requesty.ai/anthropic/v1"),
    )
    val all get() = forLanguage(LanguagePreferences.current)
    fun sites(id: String): List<GatewayProvider> = variants.filter { it.id == id }
    fun forLanguage(language: AppLanguage): List<GatewayProvider> {
        val site = if (language == AppLanguage.CHINESE) GatewaySite.CHINA else GatewaySite.INTERNATIONAL
        return variants.groupBy { it.id }.values.map { entries -> entries.firstOrNull { it.site == site } ?: entries.first() }
    }
    fun ordered(language: AppLanguage): List<GatewayProvider> = forLanguage(language).sortedWith(
        compareBy<GatewayProvider> { it.domestic != (language == AppLanguage.CHINESE) }
            .thenBy { if (it.id == "bailian") 0 else 1 },
    )
    fun preferred(language: AppLanguage): GatewayProvider = ordered(language).first()
    fun find(id: String, site: GatewaySite? = null): GatewayProvider? =
        if (site == null) all.firstOrNull { it.id == id } else variants.firstOrNull { it.id == id && it.site == site }
    fun matchGroup(profiles: List<GatewayProfile>): GatewayProvider? = profiles.takeIf { it.isNotEmpty() }?.let { group ->
        variants.firstOrNull { provider -> group.all { provider.candidates().forAgent(it.agent) == it.endpoint } }
    }

    /** True when [endpoint] is a known base, ignoring a trailing slash or native API suffix. */
    fun matches(agent: AgentId, endpoint: String): Boolean = match(agent, endpoint) != CUSTOM

    fun match(agent: AgentId, endpoint: String): String {
        val normalized = normalize(endpoint)
        if (normalized.isEmpty()) return CUSTOM
        return variants.firstOrNull { normalize(it.listedAddress(agent).orEmpty()) == normalized }?.id ?: CUSTOM
    }

    private fun normalize(value: String): String =
        value.trim().trimEnd('/').replace(SUFFIX, "").lowercase()

    private val SUFFIX = Regex("(?i)/(?:chat/completions|responses|messages)$")
}
