package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayModel
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings
import com.github.ytlog.mobby.android.localization.AppLanguage

/** Trial quotas belong to the user's provider account; they cannot be read from a model catalog. */
internal data class GatewayFreeOption(
    val providerId: String,
    val domestic: Boolean,
    val summary: String,
    val keyUrl: String,
    val termsUrl: String,
    val suggestedModel: String = "",
    val quotaConfirmation: Boolean = false,
) {
    val provider get() = requireNotNull(GatewayProviders.find(providerId))
    fun allowsModel(id: String): Boolean = when (providerId) {
        "openrouter" -> id == "openrouter/free" || id.endsWith(":free")
        "groq" -> id in setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b")
        "requesty" -> id in GatewayFreeOptions.requestyModels
        else -> id.isNotBlank() // Domestic trials require confirmation in the provider console.
    }
    fun models(catalog: List<GatewayModel>): List<GatewayModel> = catalog.filter { allowsModel(it.id) }
}

internal object GatewayFreeOptions {
    /** Published free model IDs, reviewed 2026-10-15; changing provider prices still needs review. */
    val requestyModels = setOf(
        "nvidia/nemotron-3-ultra-550b-a55b", "nvidia/nemotron-3-super-120b-a12b", "nvidia/nemotron-3-nano-30b-a3b",
        "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning", "poolside/laguna-xs.2", "poolside/laguna-m.1",
        "google/gemma-4-31b-it", "mistral/leanstral-1-5", "nvidia/nemotron-3.5-lightning-30b-a3b",
        "nvidia/muse-glimmer-30b", "novita/ling-3.1-flash", "novita/inclusionai/ling-3.0-tiny",
    )
    private val all get() = listOf(
        GatewayFreeOption("bailian", true, AppStrings.bailianFreeSummary,
            "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key", "https://help.aliyun.com/zh/model-studio/new-free-quota",
            quotaConfirmation = true),
        GatewayFreeOption("volcengine", true, AppStrings.arkFreeSummary,
            "https://ark.volcengine.com/region:cn-beijing/apikey", "https://docs.volcengine.com/docs/ark/free-inference-quota?lang=zh",
            quotaConfirmation = true),
        GatewayFreeOption("openrouter", false, AppStrings.openRouterFreeSummary,
            "https://openrouter.ai/settings/keys", "https://openrouter.ai/pricing", "openrouter/free"),
        GatewayFreeOption("requesty", false, AppStrings.requestyFreeSummary,
            "https://app.requesty.ai/api-keys", "https://docs.requesty.ai/features/free-models", "nvidia/nemotron-3-super-120b-a12b"),
        GatewayFreeOption("groq", false, AppStrings.groqFreeSummary,
            "https://console.groq.com/keys", "https://console.groq.com/docs/rate-limits", "openai/gpt-oss-20b"),
    )
    fun ordered(language: AppLanguage): List<GatewayFreeOption> = all.sortedBy { it.domestic != (language == AppLanguage.CHINESE) }
    fun preferred(language: AppLanguage): GatewayFreeOption = ordered(language).first()
    fun find(id: String): GatewayFreeOption? = all.firstOrNull { it.providerId == id }
}
