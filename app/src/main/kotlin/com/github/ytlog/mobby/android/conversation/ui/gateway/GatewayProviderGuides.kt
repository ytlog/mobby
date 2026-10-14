package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings
import com.github.ytlog.mobby.android.conversation.ui.LanguagePreferences
import com.github.ytlog.mobby.android.localization.AppLanguage

/** Signup help is informational; catalog and manual model selection retain the ordinary gateway behavior. */
internal data class GatewayProviderGuide(val summary: String, val keyUrl: String? = null, val helpUrl: String? = null)

internal object GatewayProviderGuides {
    fun find(id: String, site: GatewaySite? = null): GatewayProviderGuide? {
        val selectedSite = site ?: GatewayProviders.find(id)?.site ?: GatewaySite.SHARED
        val chinese = LanguagePreferences.current == AppLanguage.CHINESE
        return when (id) {
            "openrouter" -> GatewayProviderGuide(AppStrings.openRouterGatewayGuide,
                "https://openrouter.ai/settings/keys", "https://openrouter.ai/support")
            "openai" -> GatewayProviderGuide(AppStrings.openAiGatewayGuide,
                "https://platform.openai.com/api-keys", "https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform")
            "anthropic" -> GatewayProviderGuide(AppStrings.anthropicGatewayGuide,
                "https://platform.claude.com/settings/keys", "https://platform.claude.com/docs/en/get-api-key")
            "deepseek" -> GatewayProviderGuide(AppStrings.deepSeekGatewayGuide,
                "https://platform.deepseek.com/api_keys", if (chinese) "https://api-docs.deepseek.com/zh-cn/" else "https://api-docs.deepseek.com/")
            "mimo" -> GatewayProviderGuide(AppStrings.mimoGatewayGuide,
                "https://platform.xiaomimimo.com/", "https://mimo.mi.com/docs/${if (chinese) "zh-CN" else "en-US"}/quick-start/summary/first-api-call")
            "kimi" -> if (selectedSite == GatewaySite.CHINA) GatewayProviderGuide(AppStrings.kimiGatewayGuide,
                "https://platform.kimi.com/console/api-keys", "https://platform.kimi.com/docs/get-api-key")
            else GatewayProviderGuide(AppStrings.kimiGatewayGuide,
                "https://platform.kimi.ai/console/api-keys", "https://platform.kimi.ai/docs/overview")
            "zhipu" -> if (selectedSite == GatewaySite.INTERNATIONAL) GatewayProviderGuide(AppStrings.zaiGatewayGuide,
                "https://z.ai/manage-apikey/apikey-list", "https://docs.z.ai/devpack/tool/claude")
            else GatewayProviderGuide(AppStrings.zhipuGatewayGuide,
                "https://bigmodel.cn/usercenter/proj-mgmt/apikeys", "https://docs.bigmodel.cn/cn/guide/start/quick-start")
            "xai" -> GatewayProviderGuide(AppStrings.xaiGatewayGuide,
                "https://console.x.ai/", "https://docs.x.ai/developers/quickstart")
            "groq" -> GatewayProviderGuide(AppStrings.groqGatewayGuide,
                "https://console.groq.com/keys", "https://console.groq.com/docs/rate-limits")
            "bailian" -> if (selectedSite == GatewaySite.INTERNATIONAL) GatewayProviderGuide(AppStrings.modelStudioInternationalGuide,
                "https://modelstudio.console.alibabacloud.com/", "https://www.alibabacloud.com/help/en/model-studio/get-api-key")
            else GatewayProviderGuide(AppStrings.bailianGatewayGuide,
                "https://bailian.console.aliyun.com/cn-beijing/model/settings/api-key", "https://help.aliyun.com/zh/model-studio/new-free-quota")
            "volcengine" -> if (selectedSite == GatewaySite.INTERNATIONAL) GatewayProviderGuide(AppStrings.bytePlusModelArkGuide,
                "https://ai.byteplus.com/ark/region:ap-southeast-1/apikey", "https://docs.byteplus.com/en/docs/modelark/product-overview")
            else GatewayProviderGuide(AppStrings.arkGatewayGuide,
                "https://ark.volcengine.com/region:cn-beijing/apikey", "https://docs.volcengine.com/docs/ark/free-inference-quota?lang=zh")
            "requesty" -> GatewayProviderGuide(AppStrings.requestyGatewayGuide,
                "https://app.requesty.ai/api-keys", "https://docs.requesty.ai/quickstart")
            GatewayProviders.CUSTOM -> GatewayProviderGuide(AppStrings.customGatewayGuide)
            else -> null
        }
    }
}
