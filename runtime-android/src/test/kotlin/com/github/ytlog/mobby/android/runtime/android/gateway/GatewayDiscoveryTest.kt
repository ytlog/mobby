package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import com.github.ytlog.mobby.android.runtime.api.gateway.GatewayCheckOutcome
import com.github.ytlog.mobby.android.runtime.engine.AgentMode
import com.github.ytlog.mobby.android.runtime.api.gateway.GatewayCandidateAddresses
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayDiscoveryTest {
    @Test fun `only completed native protocols become supported agents and duplicate responses endpoint is probed once`() = runBlocking {
        val checked = mutableListOf<GatewayProtocol>()
        val discovery = GatewayDiscovery(
            catalog = { CatalogResult.Ready(listOf(GatewayModel("chat", "Chat"), GatewayModel("other", "Other"))) },
            probe = { config -> checked += config.protocol
                if (config.protocol == GatewayProtocol.RESPONSES) GatewayCheckOutcome.SUCCEEDED else GatewayCheckOutcome.HTTP_ERROR },
        )
        val result = discovery.inspect(GatewayCandidateAddresses("https://test.invalid/v1/responses"), "chat", "synthetic-key")
        assertEquals(setOf(AgentMode.CODEX, AgentMode.OPEN_CODE), result.supported.keys)
        assertEquals("https://test.invalid/v1", result.supported.getValue(AgentMode.CODEX))
        assertEquals(listOf(GatewayProtocol.RESPONSES, GatewayProtocol.MESSAGES), checked)
        assertEquals(listOf("chat", "other"), result.models.map { it.id })
        assertFalse(result.toString().contains("synthetic-key"))
    }

    @Test fun `missing catalog still permits manual model but never grants an unverified protocol`() = runBlocking {
        val discovery = GatewayDiscovery(
            catalog = { CatalogResult.Unavailable("该网关没有模型列表接口") },
            probe = { config -> if (config.protocol == GatewayProtocol.MESSAGES) GatewayCheckOutcome.SUCCEEDED else GatewayCheckOutcome.INVALID_RESPONSE },
        )
        val endpoints = GatewayCandidateAddresses("https://test.invalid/v1")
        val result = discovery.inspect(endpoints, "typed-model", "")
        assertEquals(setOf(AgentMode.CLAUDE), result.supported.keys)
        assertTrue(result.models.isEmpty())
        assertEquals("该网关没有模型列表接口", result.catalogError)
        val noModel = discovery.inspect(endpoints, "", "")
        assertTrue(noModel.supported.isEmpty())
    }
}
