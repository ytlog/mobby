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
class GatewayFreeExperienceTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("free-gateway-language", Context.MODE_PRIVATE)
    @After fun reset() { prefs.edit().clear().commit(); LanguagePreferences.restore(prefs) }
    private fun language(value: AppLanguage) { LanguagePreferences.select(prefs, value) }
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private val catalog = GatewayCatalogResult(listOf(GatewayModel("openrouter/free", "Free router"), GatewayModel("openai/paid", "Paid model")), null)
    private fun form(
        fetch: suspend (GatewayEdit) -> DataResult<GatewayCatalogResult> = { DataResult.Loaded(catalog) },
        signIn: suspend () -> GatewaySignInResult = { GatewaySignInResult.Failed("unused") },
        open: (String) -> Unit = {},
    ) {
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            GatewayForm(emptyList(), { task -> scope.launch { task() } }, { GatewaySaveResult.Failed("unused") }, fetch,
                {}, {}, { DataResult.Failed("unused") }, editingId = "", freeExperience = true, signIn = signIn, openLink = open)
        } }
    }
    @Test fun `Chinese trial defaults to Beijing with official key link and quota confirmation`() {
        language(AppLanguage.CHINESE)
        var opened = ""
        var fetched: GatewayEdit? = null
        form(fetch = { fetched = it; DataResult.Loaded(GatewayCatalogResult(listOf(GatewayModel("quota-model", "Quota model"), GatewayModel("other-quota-model", "Other quota model")), null)) }, open = { opened = it })
        compose.onNodeWithText("服务：阿里云百炼").assertExists()
        compose.onNodeWithText("前往官网注册 / 获取 Key").performScrollTo().performClick()
        assertEquals(GatewayFreeOptions.find("bailian")!!.keyUrl, opened)
        field("API Key（无鉴权可留空）").performScrollTo().performTextReplacement("synthetic-key")
        compose.onNodeWithText("获取模型").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("我已确认所选模型有免费额度，并已设置额度用完停止调用").assertDoesNotExist()
        compose.onNodeWithText("获取模型").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetched != null }
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", fetched!!.addresses.responses)
        assertEquals("https://dashscope.aliyuncs.com/apps/anthropic/v1", fetched!!.addresses.messages)
        compose.onNodeWithText("模型：请选择").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("保存网关").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("我已确认所选模型有免费额度，并已设置额度用完停止调用").performScrollTo().performClick()
        compose.onNodeWithText("保存网关").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("模型：quota-model").performScrollTo().performClick()
        compose.onAllNodesWithText("设为默认")[0].performClick()
        compose.onNodeWithText("保存网关").performScrollTo().assertIsNotEnabled()
    }
    @Test fun `English sign in imports only an in memory credential and hides paid models`() {
        language(AppLanguage.ENGLISH)
        val secret = "synthetic-authorized-key".toCharArray()
        var fetchedKey = ""
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) }, signIn = { GatewaySignInResult.Credential(secret) })
        compose.onNodeWithText("Service: OpenRouter").assertExists()
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.waitUntil(5_000) { secret.all { it == '\u0000' } }
        compose.onNodeWithText("Fetch models").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey.isNotEmpty() }
        assertEquals("synthetic-authorized-key", fetchedKey)
        compose.onNodeWithText("Model: openrouter/free").performScrollTo().performClick()
        compose.onNodeWithText("Paid model").assertDoesNotExist()
        compose.onNodeWithText("Add model").performClick()
        field("Model ID").performTextReplacement("openai/paid")
        compose.onNodeWithText("Add", useUnmergedTree = true).performClick()
        compose.onNodeWithText("This setup only accepts this provider's free model IDs.").assertExists()
    }
    @Test fun `cancelling sign in releases controls and never fetches or saves a gateway`() {
        language(AppLanguage.CHINESE)
        var signInCancelled = false
        form(signIn = { try { awaitCancellation() } finally { signInCancelled = true } })
        compose.onNodeWithText("服务：阿里云百炼").performClick()
        compose.onNodeWithText("OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("登录 OpenRouter 获取 Key").performScrollTo().performClick()
        compose.onNodeWithText("取消登录").performScrollTo().performClick()
        compose.waitUntil(5_000) { signInCancelled }
        compose.onNodeWithText("登录已取消，未保存网关。").assertExists()
        compose.onNodeWithText("保存网关").assertDoesNotExist()
        compose.onNodeWithText("服务：OpenRouter").assertIsEnabled()
    }
    @Test fun `reselecting the same provider retains its key but switching provider clears it`() {
        language(AppLanguage.ENGLISH)
        var fetchedKey = ""
        form(fetch = { fetchedKey = it.credential!!.concatToString(); DataResult.Loaded(catalog) },
            signIn = { GatewaySignInResult.Credential("synthetic-key".toCharArray()) })
        compose.onNodeWithText("Sign in with OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Service: OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("OpenRouter").performClick()
        compose.onNodeWithText("Fetch models").performScrollTo().performClick()
        compose.waitUntil(5_000) { fetchedKey.isNotEmpty() }
        assertEquals("synthetic-key", fetchedKey)
        compose.onNodeWithText("Service: OpenRouter").performScrollTo().performClick()
        compose.onNodeWithText("Requesty").performClick()
        compose.onNodeWithText("Fetch models").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save gateway").assertDoesNotExist()
    }
}
