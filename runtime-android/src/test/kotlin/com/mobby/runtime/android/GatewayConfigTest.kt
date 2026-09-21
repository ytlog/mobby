package com.mobby.runtime.android

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
        for (mode in listOf(com.mobby.runtime.engine.AgentMode.CODEX, com.mobby.runtime.engine.AgentMode.CLAUDE)) {
            assertThrows(IllegalArgumentException::class.java) { legacy.validateFor(mode) }
            val native = legacy.copy(protocol = if (mode == com.mobby.runtime.engine.AgentMode.CODEX) GatewayProtocol.RESPONSES else GatewayProtocol.MESSAGES)
            native.validateFor(mode)
            assertThrows(IllegalArgumentException::class.java) { native.validateFor(if (mode == com.mobby.runtime.engine.AgentMode.CODEX) com.mobby.runtime.engine.AgentMode.CLAUDE else com.mobby.runtime.engine.AgentMode.CODEX) }
        }
    }

}
