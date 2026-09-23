package com.github.ytlog.mobby.android.runtime.android

import org.junit.Assert.*
import org.junit.Test

class GatewayConfigTest {
    @Test fun roundtripKeepsProtocolAndLiteralValues() {
        val original = GatewayConfig("https://example.com/v1", "model-name", "key-with-quote\"", GatewayProtocol.CHAT)
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
        GatewayConfig("http://192.168.1.2:8080/v1/chat/completions", "local", "", GatewayProtocol.CHAT).validate()
    }
    @Test fun protocolMustMatchAgentWithoutRewritingLegacyConfig() {
        val legacy = GatewayConfig("https://example.com/v1", "model", "fake", GatewayProtocol.CHAT)
        assertEquals(legacy, GatewayConfig.parse(legacy.json()))
        for (mode in listOf(com.github.ytlog.mobby.android.runtime.engine.AgentMode.CODEX, com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE, com.github.ytlog.mobby.android.runtime.engine.AgentMode.OPEN_CODE)) {
            assertThrows(IllegalArgumentException::class.java) { legacy.validateFor(mode) }
            val native = legacy.copy(protocol = mode.gatewayProtocol())
            native.validateFor(mode)
            val other = if (mode == com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE) com.github.ytlog.mobby.android.runtime.engine.AgentMode.OPEN_CODE else com.github.ytlog.mobby.android.runtime.engine.AgentMode.CLAUDE
            assertThrows(IllegalArgumentException::class.java) { native.validateFor(other) }
        }
    }

    @Test fun `legacy config without a catalog still loads and only accepts its saved model`() {
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
