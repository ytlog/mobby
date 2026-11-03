package com.github.ytlog.mobby.android.runtime.engine

import org.junit.Assert.*
import org.junit.Test

class NativeAuthenticationFailureTest {
    @Test fun `native retry progress does not expire login and a successful retry clears a prior model error`() {
        assertNull(NativeAuthenticationFailure.terminalState("""{"type":"auto_retry_start","error":"HTTP 401"}"""))
        assertEquals(true, NativeAuthenticationFailure.terminalState("""{"type":"message_end","message":{"stopReason":"error","errorMessage":"HTTP 401"}}"""))
        assertEquals(false, NativeAuthenticationFailure.terminalState("""{"type":"message_end","message":{"stopReason":"stop","role":"assistant","content":[]}}"""))
        assertEquals(false, NativeAuthenticationFailure.terminalState("""{"type":"turn.completed"}"""))
        assertEquals(false, NativeAuthenticationFailure.terminalState("""{"type":"step_finish","part":{"reason":"stop"}}"""))
    }

    @Test fun `only an explicit protocol auth error expires a native login`() {
        assertTrue(NativeAuthenticationFailure.matches("""{"error":{"code":"invalid_grant"}}"""))
        assertTrue(NativeAuthenticationFailure.matches("""{"method":"turn/completed","params":{"turn":{"error":{"message":"HTTP 401"}}}}"""))
        assertTrue(NativeAuthenticationFailure.matches("""{"type":"message_end","message":{"stopReason":"error","errorMessage":"authentication_error"}}"""))
        assertFalse(NativeAuthenticationFailure.matches("""{"type":"assistant","message":{"content":"Explain HTTP 401 and invalid_grant"}}"""))
        assertFalse(NativeAuthenticationFailure.matches("""{"error":{"message":"HTTP 429 quota exceeded"}}"""))
        assertFalse(NativeAuthenticationFailure.matches("""{"error":{"message":"HTTP 403 access denied"}}"""))
        assertFalse(NativeAuthenticationFailure.matches("""{"error":{"message":"network timeout"}}"""))
        assertFalse(NativeAuthenticationFailure.matches("""{"error":{"message":"Cannot read file 401.txt"}}"""))
        assertTrue(NativeAuthenticationFailure.matches("""{"error":{"data":{"statusCode":401}}}"""))
    }
}
