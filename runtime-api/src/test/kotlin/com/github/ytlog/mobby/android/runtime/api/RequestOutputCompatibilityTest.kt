package com.github.ytlog.mobby.android.runtime.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RequestOutputCompatibilityTest {
    @Test fun `ordinary request encoding retains previous idempotency digest input`() {
        val old = """{"requestId":"request","agentId":"CODEX","workspaceRef":"default","inputParts":[{"type":"com.github.ytlog.mobby.android.runtime.api.InputPart.Text","text":"hello"}],"modelId":"model","gatewayProfileRef":{"id":"CODEX","version":0}}"""
        val restored = Json.decodeFromString<RunRequest>(old)
        assertEquals(RequestedOutput.TEXT, restored.requestedOutput)
        assertEquals(old, Json.encodeToString(restored))
        assertTrue(Json.encodeToString(restored.copy(requestedOutput = RequestedOutput.SKILL_PROPOSAL)).contains("SKILL_PROPOSAL"))
    }
}
