package com.github.ytlog.mobby.android.conversation.ui.gateway

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import com.github.ytlog.mobby.android.conversation.ui.LanguagePreferences
import com.github.ytlog.mobby.android.localization.AppLanguage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h1000dp-port")
class GatewayProviderGuidanceTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("provider-guide-language", Context.MODE_PRIVATE)
    @After fun reset() { prefs.edit().clear().commit(); LanguagePreferences.restore(prefs) }
    private fun language(value: AppLanguage) { LanguagePreferences.select(prefs, value) }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private val catalog = GatewayCatalogResult(listOf(GatewayModel("openrouter/free", "Free router"), GatewayModel("openai/paid", "Paid model")), null)
    private fun form(
        fetch: suspend (GatewayEdit) -> DataResult<GatewayCatalogResult> = { DataResult.Loaded(catalog) },
        save: suspend (GatewayEdit) -> GatewaySaveResult = { GatewaySaveResult.Failed("unused") },
        signIn: suspend () -> GatewaySignInResult = { GatewaySignInResult.Failed("unused") },
        open: (String) -> Unit = {},
        profiles: List<GatewayProfile> = emptyList(),
    ) {
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            GatewayForm(profiles, { task -> scope.launch { task() } }, save, fetch,
                {}, {}, { DataResult.Failed("unused") }, editingId = profiles.firstOrNull()?.id ?: "", signIn = signIn, openLink = open)
        } }
    }
    @Test fun `Chinese ordinary form defaults to Beijing and saves without a trial mode`() {
        language(AppLanguage.CHINESE)
        var saved: GatewayEdit? = null
        form(fetch = { DataResult.Loaded(GatewayCatalogResult(listOf(GatewayModel("selected-model", "Selected model")), null)) },
            save = { saved = it; GatewaySaveResult.Failed("test saved") })
        compose.onNodeWithText("添加网关").assertExists()
        compose.onNodeWithText("免费体验").assertDoesNotExist()
        compose.onNodeWithText("服务：阿里云百炼").assertExists()
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("我已确认所选模型有免费额度，并已设置额度用完停止调用").assertDoesNotExist()
        compose.onNodeWithText("保存网关").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", saved!!.addresses.responses)
        assertEquals("https://dashscope.aliyuncs.com/apps/anthropic/v1", saved!!.addresses.messages)
        assertEquals("selected-model", saved!!.model)
    }
    @Test fun `English sign in preserves full model catalog and manual model choice`() {
        language(AppLanguage.ENGLISH)
        val secret = "synthetic-authorized-key".toCharArray()
        var fetchedKey = ""
        var saved: GatewayEdit? = null
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) },
            save = { saved = it; GatewaySaveResult.Failed("test saved") }, signIn = { GatewaySignInResult.Credential(secret) })
        compose.onNodeWithText("Service: OpenRouter").assertExists()
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.waitUntil(5_000) { secret.all { it == '\u0000' } }
        compose.onNodeWithText("Fetch models").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey.isNotEmpty() }
        assertEquals("synthetic-authorized-key", fetchedKey)
        compose.onNodeWithText("Model: Select").performScrollTo().performClick()
        compose.onNodeWithText("Paid model").assertExists()
        compose.onAllNodesWithText("Set as default")[1].performClick()
        compose.onNodeWithText("Model: openai/paid").performScrollTo().performClick()
        compose.onNodeWithText("Add model").performClick()
        field("Model ID").performTextReplacement("openai/manual")
        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save gateway").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("openai/paid", saved!!.model)
        assertEquals(setOf("openai/paid", "openai/manual"), saved!!.selectedModels)
    }
    @Test fun `every old and new provider opens its own official guide and key page`() {
        language(AppLanguage.CHINESE)
        var opened = ""
        form(open = { opened = it })
        var selected = "阿里云百炼"
        for (provider in GatewayProviders.ordered(AppLanguage.CHINESE)) {
            compose.onNodeWithText("服务：$selected").performScrollTo().performClick()
            compose.onNodeWithText(provider.label).performScrollTo().performClick()
            selected = provider.label
            val guide = GatewayProviderGuides.find(provider.id)!!
            compose.onNodeWithText(guide.summary).assertExists()
            compose.onNodeWithText("前往官网注册 / 获取 Key").performScrollTo().performClick()
            assertEquals(provider.id, guide.keyUrl, opened)
            compose.onNodeWithText("查看官方使用说明").performScrollTo().performClick()
            assertEquals(provider.id, guide.helpUrl, opened)
        }
    }
    @Test fun `cancelling authorization restores the ordinary form without saving`() {
        language(AppLanguage.ENGLISH)
        var cancelled = false
        var saves = 0
        form(signIn = { try { awaitCancellation() } finally { cancelled = true } },
            save = { saves++; GatewaySaveResult.Failed("unused") })
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Cancel sign-in").performScrollTo().performClick()
        compose.waitUntil(5_000) { cancelled }
        compose.onNodeWithText("Sign-in cancelled. No gateway was saved.").assertExists()
        compose.onNodeWithText("Service: OpenRouter").assertIsEnabled()
        assertEquals(0, saves)
    }
    @Test fun `same provider retains pending key and changing provider clears it`() {
        language(AppLanguage.ENGLISH)
        var fetchedKey: String? = null
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) },
            signIn = { GatewaySignInResult.Credential("synthetic-key".toCharArray()) })
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Service: OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("OpenRouter").performClick()
        compose.onNodeWithText("Fetch models").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey != null }
        assertEquals("synthetic-key", fetchedKey)
        compose.onNodeWithText("Service: OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Requesty").performScrollTo().performClick()
        compose.onNodeWithText("Fetch models").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey == "" }
        compose.onNodeWithText("Model: Select").assertExists()
    }
    @Test fun `saved international Kimi keeps its site until the user selects China`() {
        language(AppLanguage.CHINESE)
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://api.moonshot.ai/v1", "kept-model", "RESPONSES", true)
        var opened = ""
        var fetched: GatewayEdit? = null
        form(profiles = listOf(profile), open = { opened = it }, fetch = { fetched = it; DataResult.Loaded(catalog) })
        compose.onNodeWithText("国际站").performScrollTo().assertIsSelected()
        compose.onNodeWithText("前往官网注册 / 获取 Key").performScrollTo().performClick()
        assertEquals("https://platform.kimi.ai/console/api-keys", opened)
        compose.onNodeWithText("国内站").performScrollTo().performClick()
        compose.onNodeWithText("前往官网注册 / 获取 Key").performScrollTo().performClick()
        assertEquals("https://platform.kimi.com/console/api-keys", opened)
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetched != null }
        assertEquals("https://api.moonshot.cn/v1", fetched!!.addresses.responses)
        assertEquals("https://api.moonshot.cn/anthropic/v1", fetched!!.addresses.messages)
        assertNotNull(fetched!!.credential)
        assertEquals(0, fetched!!.credential!!.size)
        assertEquals("kept-model", fetched!!.model)
    }
    @Test fun `existing provider guide does not replace the saved model or address`() {
        language(AppLanguage.CHINESE)
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://api.moonshot.ai/v1", "kept-model", "RESPONSES", true)
        var fetched: GatewayEdit? = null
        form(profiles = listOf(profile), fetch = { fetched = it; DataResult.Loaded(catalog) })
        compose.onNodeWithText("编辑网关").assertExists()
        compose.onNodeWithText("服务：Kimi").assertExists()
        compose.onNodeWithText(GatewayProviderGuides.find("kimi")!!.summary).assertExists()
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetched != null }
        assertNull(fetched!!.credential)
        assertEquals(profile.model, fetched!!.model)
        assertEquals(profile.endpoint, fetched!!.addresses.responses)
    }
}
