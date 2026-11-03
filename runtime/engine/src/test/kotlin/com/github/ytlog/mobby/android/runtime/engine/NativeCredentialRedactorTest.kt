package com.github.ytlog.mobby.android.runtime.engine

import org.junit.Assert.*
import org.junit.Test

class NativeCredentialRedactorTest {
    @Test fun `redactor retains tokens across refresh and handles all four native formats without exposing unknown fields`() {
        val redactor = NativeCredentialRedactor("""{"tokens":{"access_token":"synthetic-codex-token","refresh_token":"synthetic-codex-refresh"}}""")
        redactor.include("""{"claudeAiOauth":{"accessToken":"synthetic-claude-token","refreshToken":"synthetic-claude-refresh"}}""")
        redactor.include("""{"openai":{"type":"oauth","access":"synthetic-opencode-token","refresh":"synthetic-opencode-refresh"}}""")
        redactor.include("""{"openai-codex":{"type":"oauth","access":"synthetic-pi-token","refresh":"synthetic-pi-refresh"}}""")
        assertEquals("[redacted] [redacted] [redacted] [redacted]", redactor.redact("synthetic-codex-token synthetic-claude-token synthetic-opencode-token synthetic-pi-token"))
        assertEquals("public message", redactor.redact("public message"))
    }
}
