package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.RequestId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenCodeServerSessionTest {
    @Test fun `live server takes another prompt on the same input after terminal event`() = runBlocking {
        val session = OpenCodeServerSession("model", "ses_existing")
        session.submit(AgentTurn(RequestId("r"), "hello"))
        assertTrue(session.input.first().decodeToString().contains("\"text\":\"hello\""))
        assertEquals("ses_existing", session.sessionId())
        assertFalse(session.takeTurnEnded())
        assertEquals(listOf("""{"type":"step_finish","part":{"reason":"tool-calls"}}"""),
            session.onStdout("""{"type":"step_finish","part":{"reason":"tool-calls"}}"""))
        assertFalse(session.takeTurnEnded())
        session.onStdout("""{"type":"step_finish","part":{"reason":"stop"}}""")
        assertTrue(session.takeTurnEnded())
        assertFalse(session.takeTurnEnded())
        session.submit(AgentTurn(RequestId("next"), "follow up"))
        assertTrue(session.input.first().decodeToString().contains("\"text\":\"follow up\""))
        assertFalse(session.abandonAfterTurn())
        session.close()
    }

    @Test fun `new server session id is carried into the next prompt`() = runBlocking {
        val session = OpenCodeServerSession("model", null)
        session.submit(AgentTurn(RequestId("first"), "one"))
        assertFalse(session.input.first().decodeToString().contains("sessionId"))
        session.onStdout("""{"type":"step_start","sessionID":"ses_new"}""")
        session.onStdout("""{"type":"step_finish","part":{"reason":"stop"}}""")
        assertTrue(session.takeTurnEnded())
        session.submit(AgentTurn(RequestId("second"), "two"))
        assertTrue(session.input.first().decodeToString().contains("\"sessionId\":\"ses_new\""))
        session.close()
    }
}
