package com.github.ytlog.mobby.android.conversation.ui.gateway

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
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
    private fun selectOpenRouter() = compose.onNodeWithText("OpenRouter").performScrollTo().performClick()
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
    @Test fun `Chinese new setup preselects nothing and saves the entered address`() {
        language(AppLanguage.CHINESE)
        var saved: GatewayEdit? = null
        form(fetch = { DataResult.Loaded(GatewayCatalogResult(listOf(GatewayModel("selected-model", "Selected model")), null)) },
            save = { saved = it; GatewaySaveResult.Failed("test saved") })
        compose.onNodeWithText("添加 AI 服务").assertExists()
        compose.onNodeWithText("免费体验").assertDoesNotExist()
        compose.onNodeWithText("其他服务（填写地址）").assertIsNotSelected().performScrollTo().performClick()
        field("服务地址").assertIsEnabled()
        assertEquals("", field("服务地址").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("连接").assertIsNotEnabled()
        field("服务地址").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("连接").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("https://gateway.invalid/v1", saved!!.addresses.responses)
        assertEquals("https://gateway.invalid/v1", saved!!.addresses.messages)
        assertEquals("selected-model", saved!!.model)
        compose.onNodeWithText("test saved").assertExists()
    }
    @Test fun `English other service has an editable empty address`() {
        language(AppLanguage.ENGLISH)
        var fetched: GatewayEdit? = null
        form(fetch = { fetched = it; DataResult.Loaded(catalog) })
        compose.onNodeWithText("Other service (enter address)").performScrollTo().performClick()
        assertEquals("", field("Service address").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        field("Service address").performScrollTo().performTextReplacement("https://gateway.invalid/v1")
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetched != null }
        assertEquals("https://gateway.invalid/v1", fetched!!.addresses.responses)
        assertEquals("https://gateway.invalid/v1", fetched!!.addresses.messages)
    }
    @Test fun `English sign in keeps the full catalog, never picks the free route and accepts a typed model`() {
        language(AppLanguage.ENGLISH)
        val secret = "synthetic-authorized-key".toCharArray()
        var fetchedKey = ""
        var saved: GatewayEdit? = null
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) },
            save = { saved = it; GatewaySaveResult.Failed("test saved") }, signIn = { GatewaySignInResult.Credential(secret) })
        selectOpenRouter()
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.waitUntil(5_000) { secret.all { it == '\u0000' } }
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey.isNotEmpty() }
        assertEquals("synthetic-authorized-key", fetchedKey)
        assertNull(saved)
        compose.onNodeWithText("Choose a model to finish connecting").assertExists()
        compose.onNodeWithText("Free router · openrouter/free").assertExists()
        compose.onNodeWithText("Paid model · openai/paid").assertExists()
        compose.onNodeWithText("Enter another model ID").performScrollTo().performClick()
        field("Model ID").performScrollTo().performTextReplacement("openai/manual")
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("openai/manual", saved!!.model)
        assertEquals(setOf("openrouter/free", "openai/paid", "openai/manual"), saved!!.selectedModels)
    }
    @Test fun `every provider opens its own key page and, under advanced settings, its guide`() {
        language(AppLanguage.CHINESE)
        var opened = ""
        form(open = { opened = it })
        for (provider in GatewayProviders.ordered(AppLanguage.CHINESE)) {
            compose.onNodeWithText(provider.label).performScrollTo().performClick()
            val guide = GatewayProviderGuides.find(provider.id)!!
            compose.onNodeWithText(guide.summary).assertExists()
            compose.onNodeWithText("还没有 Key？去官网领取").performScrollTo().performClick()
            assertEquals(provider.id, guide.keyUrl, opened)
            if (compose.onAllNodesWithText("查看官方使用说明").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithText("高级设置").performScrollTo().performClick()
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
        selectOpenRouter()
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Cancel sign-in").performScrollTo().performClick()
        compose.waitUntil(5_000) { cancelled }
        compose.onNodeWithText("Sign-in cancelled. No AI service was saved.").assertExists()
        compose.onNodeWithText("OpenRouter").assertIsEnabled()
        assertEquals(0, saves)
    }
    @Test fun `same provider retains pending key and changing provider clears it`() {
        language(AppLanguage.ENGLISH)
        var fetchedKey: String? = null
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) },
            signIn = { GatewaySignInResult.Credential("synthetic-key".toCharArray()) })
        selectOpenRouter()
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        selectOpenRouter()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey != null }
        assertEquals("synthetic-key", fetchedKey)
        compose.onNodeWithText("Requesty").performScrollTo().performClick()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey == "" }
        compose.onNodeWithText("Choose a model to finish connecting").assertExists()
    }
    @Test fun `saved international Kimi keeps its site until the user selects China`() {
        language(AppLanguage.CHINESE)
        val profile = GatewayProfile(AgentId.CODEX, "saved", 2, "https://api.moonshot.ai/v1", "kept-model", "RESPONSES", true)
        var opened = ""
        var fetched: GatewayEdit? = null
        form(profiles = listOf(profile), open = { opened = it }, fetch = { fetched = it; DataResult.Loaded(catalog) })
        compose.onNodeWithText("高级设置").performScrollTo().performClick()
        compose.onNodeWithText("国际站").performScrollTo().assertIsSelected()
        compose.onNodeWithText("还没有 Key？去官网领取").performScrollTo().performClick()
        assertEquals("https://platform.kimi.ai/console/api-keys", opened)
        compose.onNodeWithText("国内站").performScrollTo().performClick()
        compose.onNodeWithText("还没有 Key？去官网领取").performScrollTo().performClick()
        assertEquals("https://platform.kimi.com/console/api-keys", opened)
        compose.onNodeWithText("连接").performScrollTo().performClick()
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
        compose.onNodeWithText("编辑 AI 服务").assertExists()
        compose.onNodeWithText("Kimi").assertIsSelected()
        compose.onNodeWithText(GatewayProviderGuides.find("kimi")!!.summary).assertExists()
        compose.onNodeWithText("连接").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetched != null }
        assertNull(fetched!!.credential)
        assertEquals(profile.model, fetched!!.model)
        assertEquals(profile.endpoint, fetched!!.addresses.responses)
    }
}
