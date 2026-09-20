package com.mobby.interaction.data

import com.mobby.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class RunProjectionTest {
    private val config = RunConfigSnapshot(AgentId.CODEX, WorkspaceRef("default"), "model", null, GatewayProfileRef("CODEX", 0), emptySet())
    private val initial = RunSnapshot(RunId("r"), RunPhase.RUNNING, 1, 1, config)
    private fun event(sequence: Long, payload: RuntimeEvent, run: String = "r") = EventEnvelope("event-$sequence", RunId(run), sequence, 0, payload)
    @Test fun `duplicates do not append and gaps require resync`() {
        val part = OutputSegment("m", 0, ResourceRef("r/0"))
        val delta = event(2, RuntimeEvent.AssistantDelta(part))
        val updated = RunProjection.apply(initial, delta)!!
        assertEquals(1, RunProjection.apply(updated, delta)!!.outputSegments.size)
        assertNull(RunProjection.apply(updated, event(4, RuntimeEvent.AssistantDelta(part))))
        assertNull(RunProjection.apply(updated, event(3, RuntimeEvent.AssistantDelta(part), "other")))
        assertNull(RunProjection.apply(updated, event(3, RuntimeEvent.AssistantDelta(part)).copy(apiMajor = 2)))
    }
    @Test fun `message completion is not run completion and session event cannot undo cancelling`() {
        assertEquals(RunPhase.RUNNING, RunProjection.apply(initial, event(2, RuntimeEvent.AssistantCompleted("m")))!!.phase)
        val stopping = RunProjection.apply(initial, event(2, RuntimeEvent.CancellationRequested))!!
        assertEquals(RunPhase.CANCELLING, RunProjection.apply(stopping, event(3, RuntimeEvent.RunStarted(SessionRef("session"))))!!.phase)
    }
    @Test fun `success without evidence is never projected as success`() {
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED)))
        assertEquals(RunPhase.SUCCEEDED, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED, terminalEvidence = TerminalEvidence(true, 0))))
        assertTrue(RunProjection.occupied(initial.copy(phase = RunPhase.OUTCOME_UNKNOWN)))
    }
}
