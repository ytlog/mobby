package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.runtime.engine.AgentMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalModelGatewayTest {
    @After fun reset() {
        LocalModelGateway.healthReader = { null }
        LocalModelGateway.clearSelection()
    }

    @Test fun loadedModelIsTemporaryGatewayAndDisappearsOnUnloadOrStop() {
        val context = RuntimeEnvironment.getApplication()
        val store = GatewayStore(context)
        LocalModelGateway.configure("fake-test-token")
        var health: String? = """{"instanceId":"first","status":"LISTENING","loadedModel":null}"""
        LocalModelGateway.healthReader = { key -> assertEquals("fake-test-token", key); health }
        assertTrue(store.list().isEmpty())

        health = """{"instanceId":"first","status":"LISTENING","loadedModel":"qwen-test"}"""
        val active = store.list().single()
        assertEquals(LocalModelGateway.ID, active.id)
        assertEquals("qwen-test", active.model)
        assertTrue(GatewayConfig.parse(active.config(AgentMode.CODEX).json()).localAgentProfile)
        assertThrows(IllegalArgumentException::class.java) { store.save(active) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(active.id) }
        store.selectDefault(GatewayChoice(active.id, AgentMode.CODEX))
        assertEquals(active.id, store.default()?.id)

        health = """{"instanceId":"first","status":"LISTENING","loadedModel":null}"""
        assertTrue(store.list().isEmpty())
        assertNull(store.default())
        assertThrows(IllegalArgumentException::class.java) { store.load(LocalModelGateway.ID) }

        health = """{"instanceId":"second","status":"LISTENING","loadedModel":"qwen-test"}"""
        assertEquals(LocalModelGateway.ID, store.list().single().id)
        assertNull(store.default())

        health = null
        assertTrue(store.list().isEmpty())
        assertNull(store.default())
        assertTrue(store.ids().isEmpty())
    }
}
