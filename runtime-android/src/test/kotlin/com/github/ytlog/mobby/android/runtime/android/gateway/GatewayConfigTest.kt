package com.github.ytlog.mobby.android.runtime.android.gateway

import org.junit.Assert.*
import org.junit.Test
import com.github.ytlog.mobby.android.runtime.engine.AgentMode

class GatewayConfigTest {
    @Test fun `one gateway stores separate native endpoints and roundtrips without losing credentials`() {
        val original = GatewayRecord("e1335130-8548-4c86-935c-b10918180006", 3,
            mapOf(AgentMode.CODEX to "https://example.test/v1", AgentMode.CLAUDE to "https://example.test/anthropic/v1"),
            "shared-model", "synthetic-key", listOf(GatewayModel("shared-model", "Shared")))
        original.validate()
        val restored = GatewayRecord.parse(original.json())
        assertEquals(original, restored)
        assertEquals(GatewayProtocol.RESPONSES, restored.config(AgentMode.CODEX).protocol)
        assertEquals(GatewayProtocol.MESSAGES, restored.config(AgentMode.CLAUDE).protocol)
        assertThrows(IllegalArgumentException::class.java) { restored.config(AgentMode.OPEN_CODE) }
    }
    @Test fun roundtripKeepsProtocolAndLiteralValues() {
        val original = GatewayConfig("https://example.com/v1", "model-name", "key-with-quote\"", GatewayProtocol.RESPONSES)
        original.validate()
        assertEquals(original, GatewayConfig.parse(original.json()))
    }
    @Test fun rejectCredentialUrlsAndInvalidModels() {
        for (url in listOf("", "file:///tmp/file", "https://secret@example.com/v1", "https://example.com?key=secret", "https://example.com/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { GatewayConfig(url, "model").validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig("https://example.com", "\nmodel").validate() }
        assertThrows(IllegalArgumentException::class.java) { GatewayConfig("https://example.com", "model", "key\r\nheader").validate() }
    }
    @Test fun allowLocalGatewaysAndKeylessGateways() {
        GatewayConfig("http://192.168.1.2:8080/v1/responses", "local", "", GatewayProtocol.RESPONSES).validate()
    }
    @Test fun protocolMustMatchAgent() {
        for (mode in listOf(com.github.ytlog.mobby.android.runtime.engine.AgentMode.CODEX, com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE, com.github.ytlog.mobby.android.runtime.engine.AgentMode.OPEN_CODE)) {
            val native = GatewayConfig("https://example.com/v1", "model", "fake", mode.gatewayProtocol())
            native.validateFor(mode)
            val other = if (mode == com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE) com.github.ytlog.mobby.android.runtime.engine.AgentMode.OPEN_CODE else com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE
            assertThrows(IllegalArgumentException::class.java) { native.validateFor(other) }
        }
    }

    @Test fun `config without a catalog only accepts its saved model`() {
        val parsed = GatewayConfig.parse("""{"endpoint":"https://example.com/v1","model":"only","key":"k","protocol":"responses"}""")
        assertTrue(parsed.models.isEmpty())
        assertNull(parsed.catalogError)
        assertTrue(parsed.accepts("only"))
        assertFalse(parsed.accepts("other"))
        assertEquals("only", parsed.forRun("only").model)
    }

    @Test fun `catalog roundtrip keeps the default model and a selected model does not rewrite the stored default`() {
        val original = GatewayConfig("https://example.com/v1", "default", "key\"", GatewayProtocol.RESPONSES,
            listOf(GatewayModel("default", "Default"), GatewayModel("other", "Other")), null)
        val parsed = GatewayConfig.parse(original.json())
        assertEquals(original, parsed)
        assertEquals("other", original.forRun("other").model)
        assertEquals("default", original.model)
        assertTrue(original.forRun("other").models.isEmpty())
        assertFalse(original.forRun("other").json().contains("Other"))
        assertFalse(original.accepts("missing"))
    }

    @Test fun `catalog failure note roundtrips and invalid model entries are dropped`() {
        val parsed = GatewayConfig.parse("""{"endpoint":"https://example.com/v1","model":"only","key":"k","protocol":"responses","catalogError":"该网关没有模型列表接口","models":[{"id":"ok","name":"Ok"},{"id":"bad${'\n'}name","name":"Bad"},{"name":"missing"}]}""")
        assertEquals("该网关没有模型列表接口", parsed.catalogError)
        assertEquals(listOf(GatewayModel("ok", "Ok")), parsed.models)
    }

}
