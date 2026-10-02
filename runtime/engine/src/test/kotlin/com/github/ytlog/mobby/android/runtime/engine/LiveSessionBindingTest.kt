package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class LiveSessionBindingTest {
    @Test fun `a new session may reuse only the same execution boundary`() {
        val binding = LiveSessionBinding(AgentId.PI, WorkspaceRef("one"), "model", GatewayProfileRef("gateway", 1),
            setOf(CapabilityRef("skill:fixture")), RequestedOutput.TEXT, "prior")
        val request = RunRequest(RequestId("next"), AgentId.PI, WorkspaceRef("one"), emptyList(), "model",
            GatewayProfileRef("gateway", 1), capabilityRefs = setOf(CapabilityRef("skill:fixture")))
        assertTrue(binding.acceptsNewSession(request))
        assertFalse(binding.acceptsNewSession(request.copy(sessionRef = SessionRef("prior"))))
        assertFalse(binding.acceptsNewSession(request.copy(workspaceRef = WorkspaceRef("two"))))
        assertFalse(binding.acceptsNewSession(request.copy(modelId = "another-model")))
        assertFalse(binding.acceptsNewSession(request.copy(gatewayProfileRef = GatewayProfileRef("gateway", 2))))
        assertFalse(binding.acceptsNewSession(request.copy(capabilityRefs = emptySet())))
        assertFalse(binding.acceptsNewSession(request.copy(agentId = AgentId.CLAUDE_CODE)))
    }
}
