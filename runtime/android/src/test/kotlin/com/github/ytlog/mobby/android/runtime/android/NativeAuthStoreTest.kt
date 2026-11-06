package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.api.NativeModelSummary
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NativeAuthStoreTest {
    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val models = listOf(NativeModelSummary("account-model", "Account model", setOf("high")))
    private val credential = """{"tokens":{"access_token":"synthetic-native-token","unknown":{"preserved":true}}}"""
    private fun store() = NativeAuthStore(context) { key }
    @Before fun clear() {
        context.getSharedPreferences("native-agent-connections", 0).edit().clear().commit()
        store().clearAbandonedLeases()
    }
    @Test fun `login and strict source choices survive reopening without touching gateway or conversation storage`() {
        val gateway = context.getSharedPreferences("gateway_routes", 0)
        gateway.edit().putString("existing", "existing-encrypted-gateway-fixture").commit()
        val history = File(context.filesDir, "conversation-preservation-fixture").apply { writeText("existing conversation") }
        store().saveLogin(AgentId.CODEX, "openai", "ChatGPT", models, credential)
        store().save(AgentConnectionSetting(AgentId.CODEX, AgentConnectionMode.API_ONLY, "existing-api"))
        val reopened = store()
        assertEquals("ChatGPT", reopened.accounts().single().label)
        assertEquals(models, reopened.accounts().single().models)
        assertEquals(AgentConnectionMode.API_ONLY, reopened.settings().first { it.agent == AgentId.CODEX }.mode)
        assertEquals(AgentConnectionMode.AUTO, reopened.settings().first { it.agent == AgentId.PI }.mode)
        assertEquals("existing-encrypted-gateway-fixture", gateway.getString("existing", null))
        assertEquals("existing conversation", history.readText())
        val persisted = context.getSharedPreferences("native-agent-connections", 0).all.values.joinToString()
        assertFalse(persisted.contains("synthetic-native-token"));assertFalse(persisted.contains("ChatGPT"));assertFalse(persisted.contains("existing-api"))
        history.delete()
    }
    @Test fun `native credential format and unknown fields are retained and refreshed credentials are encrypted`() {
        val store = store()
        store.saveLogin(AgentId.CODEX, "openai", "ChatGPT", models, credential)
        val home = File(context.cacheDir, "native-test-home").apply { mkdirs() }
        val session = File(home, ".codex/sessions/existing-session").apply { parentFile!!.mkdirs(); writeText("existing session") }
        val lease = store.open(AgentId.CODEX, home, expectedGeneration = 1)
        assertEquals(credential, lease.credential.readText())
        lease.credential.writeText(credential.replace("synthetic-native-token", "synthetic-refreshed-token"))
        lease.close()
        assertFalse(lease.directory.exists());assertEquals("existing session", session.readText())
        store.open(AgentId.CODEX, home, expectedGeneration = 1).use { next -> assertTrue(next.credential.readText().contains("synthetic-refreshed-token")) }
        assertFalse(context.getSharedPreferences("native-agent-connections", 0).all.values.joinToString().contains("synthetic-refreshed-token"))
    }
    @Test fun `an old execution cannot restore a logged out or replaced account`() {
        val store = store(); val home = File(context.cacheDir, "native-generation-home").apply { mkdirs() }
        store.saveLogin(AgentId.CODEX, "openai", "ChatGPT", models, credential)
        val old = store.open(AgentId.CODEX, home, expectedGeneration = 1)
        store.logout(AgentId.CODEX)
        old.credential.writeText(credential.replace("synthetic-native-token", "synthetic-old-refresh"));old.close()
        assertTrue(store.accounts().isEmpty())
        store.saveLogin(AgentId.CODEX, "openai", "ChatGPT", models, credential)
        assertEquals(2L, store.accounts().single().version)
        assertThrows(IllegalArgumentException::class.java) { store.open(AgentId.CODEX, home, expectedGeneration = 1) }
    }
    @Test fun `expiration removes only native availability and leaves API choice and credentials retained for reauthentication`() {
        val store = store()
        store.saveLogin(AgentId.CODEX, "openai", "ChatGPT", models, credential)
        store.save(AgentConnectionSetting(AgentId.CODEX, AgentConnectionMode.AUTO, "existing-api"))
        store.expire(AgentId.CODEX, 1)
        assertTrue(store.accounts().single().expired)
        assertEquals("existing-api", store.settings().first { it.agent == AgentId.CODEX }.gatewayId)
        store.logout(AgentId.CODEX)
        assertTrue(store.accounts().isEmpty())
        assertEquals("existing-api", store.settings().first { it.agent == AgentId.CODEX }.gatewayId)
    }
}
