package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.RequestId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenCodeRunSessionTest {
    @Test fun `stdin closes and only a terminal step ends the turn`() = runBlocking {
        val session = OpenCodeRunSession()
        session.submit(AgentTurn(RequestId("r"), "hello"))
        assertTrue(session.input.toList().isEmpty())
        assertNull(session.sessionId())
        assertTrue(session.abandonAfterTurn())
        assertFalse(session.takeTurnEnded())
        assertEquals(listOf("""{"type":"step_finish","part":{"reason":"tool-calls"}}"""),
            session.onStdout("""{"type":"step_finish","part":{"reason":"tool-calls"}}"""))
        assertFalse(session.takeTurnEnded())
        session.onStdout("""{"type":"step_finish","part":{"reason":"stop"}}""")
        assertTrue(session.takeTurnEnded())
        assertFalse(session.takeTurnEnded())
        session.close()
    }
}
