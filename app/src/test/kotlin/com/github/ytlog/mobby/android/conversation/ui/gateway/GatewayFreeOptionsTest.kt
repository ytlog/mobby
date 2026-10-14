package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayModel
import com.github.ytlog.mobby.android.localization.AppLanguage
import org.junit.Assert.*
import org.junit.Test

class GatewayFreeOptionsTest {
    @Test fun `Chinese recommends China while English recommends international providers`() {
        assertEquals(listOf("bailian", "volcengine", "openrouter", "requesty", "groq"), GatewayFreeOptions.ordered(AppLanguage.CHINESE).map { it.providerId })
        assertEquals(listOf("openrouter", "requesty", "groq", "bailian", "volcengine"), GatewayFreeOptions.ordered(AppLanguage.ENGLISH).map { it.providerId })
        assertEquals("bailian", GatewayFreeOptions.preferred(AppLanguage.CHINESE).providerId)
        assertEquals("openrouter", GatewayFreeOptions.preferred(AppLanguage.ENGLISH).providerId)
    }
    @Test fun `offers use native protocol bases and exclude unavailable or web only services`() {
        assertNull(GatewayFreeOptions.find("nvidia"))
        assertNull(GatewayFreeOptions.find("nanogpt"))
        for (offer in GatewayFreeOptions.ordered(AppLanguage.CHINESE)) {
            val addresses = offer.provider.candidates()
            assertTrue(addresses.responses.startsWith("https://"))
            assertTrue(offer.keyUrl.startsWith("https://"))
            assertTrue(offer.termsUrl.startsWith("https://"))
            assertEquals(addresses.responses, addresses.forAgent(AgentId.PI))
        }
        assertEquals("https://dashscope.aliyuncs.com/apps/anthropic/v1", GatewayFreeOptions.find("bailian")!!.provider.messages)
        assertEquals("https://ark.cn-beijing.volces.com/api/compatible/v1", GatewayFreeOptions.find("volcengine")!!.provider.messages)
        assertEquals("https://router.requesty.ai/anthropic/v1", GatewayFreeOptions.find("requesty")!!.provider.messages)
    }
    @Test fun `international trial menus reject paid and arbitrary policy model ids`() {
        val candidates = listOf(GatewayModel("openrouter/free", "Free router"), GatewayModel("nvidia/nemotron-test:free", "Free"),
            GatewayModel("openai/gpt-oss-20b", "GPT OSS"), GatewayModel("nvidia/nemotron-3-super-120b-a12b", "Nemotron"),
            GatewayModel("openai/gpt-paid", "Paid"), GatewayModel("policy/paid-fallback", "Policy"))
        assertEquals(listOf("openrouter/free", "nvidia/nemotron-test:free"), GatewayFreeOptions.find("openrouter")!!.models(candidates).map { it.id })
        assertEquals(listOf("openai/gpt-oss-20b"), GatewayFreeOptions.find("groq")!!.models(candidates).map { it.id })
        assertEquals(listOf("nvidia/nemotron-3-super-120b-a12b"), GatewayFreeOptions.find("requesty")!!.models(candidates).map { it.id })
        for (id in listOf("openrouter", "groq", "requesty")) {
            val option = GatewayFreeOptions.find(id)!!
            assertTrue(option.allowsModel(option.suggestedModel))
            assertFalse(option.allowsModel("policy/paid-fallback"))
        }
        assertTrue(GatewayFreeOptions.find("bailian")!!.quotaConfirmation)
        assertTrue(GatewayFreeOptions.find("volcengine")!!.quotaConfirmation)
    }
}
