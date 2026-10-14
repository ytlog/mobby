package com.github.ytlog.mobby.android.conversation.ui.gateway

import android.content.Context
import com.github.ytlog.mobby.android.conversation.ui.LanguagePreferences
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.github.ytlog.mobby.android.localization.AppLanguage
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayProviderGuidesTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("provider-guide-copy", Context.MODE_PRIVATE)
    @After fun reset() { prefs.edit().clear().commit(); LanguagePreferences.restore(prefs) }
    @Test fun `every preset has localized setup help and official key and help links`() {
        val before = LanguagePreferences.current
        try {
            for (language in AppLanguage.values()) {
                LanguagePreferences.select(prefs, language)
                for (provider in GatewayProviders.all) {
                    val guide = requireNotNull(GatewayProviderGuides.find(provider.id))
                    assertTrue(provider.id, guide.summary.isNotBlank())
                    assertTrue(provider.id, guide.keyUrl!!.startsWith("https://"))
                    assertTrue(provider.id, guide.helpUrl!!.startsWith("https://"))
                    if (language == AppLanguage.ENGLISH) assertFalse(provider.id, Regex("[\\p{IsHan}]").containsMatchIn(guide.summary))
                }
            }
        } finally { LanguagePreferences.select(prefs, before) }
        assertNotNull(GatewayProviderGuides.find(GatewayProviders.CUSTOM))
        assertNull(GatewayProviderGuides.find("nvidia"))
        assertNull(GatewayProviderGuides.find("nanogpt"))
    }
    @Test fun `Kimi signup and inference region both follow the language`() {
        LanguagePreferences.select(prefs, AppLanguage.CHINESE)
        assertEquals("https://platform.kimi.com/console/api-keys", GatewayProviderGuides.find("kimi")!!.keyUrl)
        assertEquals("https://api.moonshot.cn/v1", GatewayProviders.find("kimi")!!.responses)
        assertEquals("https://api.moonshot.cn/anthropic/v1", GatewayProviders.find("kimi")!!.messages)
        LanguagePreferences.select(prefs, AppLanguage.ENGLISH)
        assertEquals("https://platform.kimi.ai/console/api-keys", GatewayProviderGuides.find("kimi")!!.keyUrl)
        assertEquals("https://api.moonshot.ai/v1", GatewayProviders.find("kimi")!!.responses)
    }
    @Test fun `regional providers pair their console with the same inference site`() {
        val hosts = mapOf(
            "kimi" to ("platform.kimi.com" to "platform.kimi.ai"),
            "bailian" to ("bailian.console.aliyun.com" to "modelstudio.console.alibabacloud.com"),
            "volcengine" to ("ark.volcengine.com" to "ai.byteplus.com"),
            "zhipu" to ("bigmodel.cn" to "z.ai"),
        )
        for ((id, pair) in hosts) {
            val china = GatewayProviders.forLanguage(AppLanguage.CHINESE).first { it.id == id }
            val international = GatewayProviders.forLanguage(AppLanguage.ENGLISH).first { it.id == id }
            assertEquals(GatewaySite.CHINA, china.site)
            assertEquals(GatewaySite.INTERNATIONAL, international.site)
            assertEquals(pair.first, java.net.URI(GatewayProviderGuides.find(id, china.site)!!.keyUrl!!).host)
            assertEquals(pair.second, java.net.URI(GatewayProviderGuides.find(id, international.site)!!.keyUrl!!).host)
            assertNotEquals(china.candidates(), international.candidates())
        }
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", GatewayProviders.forLanguage(AppLanguage.ENGLISH).first { it.id == "bailian" }.responses)
        assertEquals("https://ark.ap-southeast.bytepluses.com/api/v3", GatewayProviders.forLanguage(AppLanguage.ENGLISH).first { it.id == "volcengine" }.responses)
        assertNull(GatewayProviders.forLanguage(AppLanguage.ENGLISH).first { it.id == "zhipu" }.responses)
        assertEquals("https://api.z.ai/api/anthropic/v1", GatewayProviders.forLanguage(AppLanguage.ENGLISH).first { it.id == "zhipu" }.messages)
    }
    @Test fun `locale only orders providers without excluding any existing choice`() {
        val chinese = GatewayProviders.ordered(AppLanguage.CHINESE)
        val english = GatewayProviders.ordered(AppLanguage.ENGLISH)
        assertEquals("bailian", chinese.first().id)
        assertEquals("openrouter", english.first().id)
        assertTrue(chinese.takeWhile { it.domestic }.size == chinese.count { it.domestic })
        assertTrue(english.takeWhile { !it.domestic }.size == english.count { !it.domestic })
        assertEquals(GatewayProviders.all.map { it.id }.toSet(), chinese.map { it.id }.toSet())
        assertEquals(GatewayProviders.all.map { it.id }.toSet(), english.map { it.id }.toSet())
    }
}
