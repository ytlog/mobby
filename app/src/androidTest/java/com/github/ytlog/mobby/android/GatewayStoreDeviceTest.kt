package com.github.ytlog.mobby.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayChoice
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayRecord
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayStore
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayProtocol
import com.github.ytlog.mobby.android.runtime.engine.AgentMode
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated preferences exercise the real device Keystore without touching user gateways. */
class GatewayStoreDeviceTest {
    @Test fun independentGatewaysKeepBindingsDefaultsAndVersionedSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "gateway-device-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(requested: String?, mode: Int): SharedPreferences =
                context.getSharedPreferences(name, mode)
        }
        try {
            val store = GatewayStore(isolated)
            val firstId = UUID.randomUUID().toString()
            val secondId = UUID.randomUUID().toString()
            val first = store.save(GatewayRecord(firstId, 0, mapOf(
                GatewayProtocol.RESPONSES to "https://one.test/v1",
                GatewayProtocol.MESSAGES to "https://one.test/anthropic/v1"), "shared", "synthetic-key"))
            val second = store.save(GatewayRecord(secondId, 0, mapOf(GatewayProtocol.RESPONSES to "https://two.test/v1"), "other", ""))
            assertEquals(2, store.list().size)
            assertEquals("https://one.test/anthropic/v1", store.load(firstId).config(AgentMode.CLAUDE).endpoint)
            assertThrows(IllegalArgumentException::class.java) { store.load(secondId).config(AgentMode.CLAUDE) }
            store.selectDefault(GatewayChoice(secondId, AgentMode.CODEX))
            assertEquals(GatewayChoice(secondId, AgentMode.CODEX), GatewayStore(isolated).default())
            val edited = store.save(first.copy(model = "new-shared"))
            assertEquals(first.version + 1, edited.version)
            assertEquals("shared", store.load(firstId, first.version).model)
            assertEquals("new-shared", store.load(firstId, edited.version).model)
            store.delete(secondId)
            assertEquals(listOf(firstId), store.ids())
            assertNull(store.default())
            assertEquals("synthetic-key", store.load(firstId).key)
            assertEquals(1L, second.version)
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
