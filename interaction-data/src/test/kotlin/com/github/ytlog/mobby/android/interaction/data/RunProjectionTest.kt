package com.github.ytlog.mobby.android.interaction.data

import com.github.ytlog.mobby.android.runtime.api.*
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
    @Test fun `pending approvals survive session updates and resolve independently until cancellation`() {
        val first = PendingApproval("first", 2, "Write", "/first")
        val second = PendingApproval("second", 3, "Write", "/second")
        val one = RunProjection.apply(initial, event(2, RuntimeEvent.ApprovalRequired(first)))!!
        val two = RunProjection.apply(one, event(3, RuntimeEvent.ApprovalRequired(second)))!!
        val session = RunProjection.apply(two, event(4, RuntimeEvent.RunStarted(SessionRef("session"))))!!
        assertEquals(RunPhase.AWAITING_APPROVAL, session.phase)
        val resolved = RunProjection.apply(session, event(5, RuntimeEvent.ApprovalResolved("first", ApprovalChoice.DENY)))!!
        assertEquals(RunPhase.AWAITING_APPROVAL, resolved.phase)
        assertEquals(listOf(second), resolved.pendingApprovals)
        val stopped = RunProjection.apply(resolved, event(6, RuntimeEvent.CancellationRequested))!!
        assertEquals(RunPhase.CANCELLING, stopped.phase)
        assertTrue(stopped.pendingApprovals.isEmpty())
        val complete = RunProjection.apply(resolved, event(6, RuntimeEvent.ApprovalResolved("second", ApprovalChoice.ALLOW_ONCE)))!!
        assertEquals(RunPhase.RUNNING, complete.phase)
        assertTrue(complete.pendingApprovals.isEmpty())
    }
    @Test fun `a tool keeps the order of its first output slot when the summary arrives later`() {
        val started = RunProjection.apply(initial, event(2, RuntimeEvent.ToolStarted("t", "snapshot", "snapshot", 4)))!!
        val described = RunProjection.apply(started, event(3, RuntimeEvent.ToolStarted("t", "snapshot", """{"cmd":"shot"}""", 4)))!!
        assertEquals(4, described.steps.single().order)
        assertEquals("""{"cmd":"shot"}""", described.steps.single().summary)
        val output = RunProjection.apply(described, event(4, RuntimeEvent.ToolOutput("t", OutputSegment("tool:t", 9, ResourceRef("r/9")))))!!
        assertEquals(4, output.steps.single().order)
        assertEquals(1, output.steps.single().output.size)
    }
    @Test fun `success without evidence is never projected as success`() {
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED)))
        assertEquals(RunPhase.SUCCEEDED, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED, terminalEvidence = TerminalEvidence(true, 0))))
        assertEquals(RunPhase.SUCCEEDED, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED, terminalEvidence = TerminalEvidence(true, null, terminationConfirmed = true))))
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED, terminalEvidence = TerminalEvidence(true, null))))
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunProjection.verifiedPhase(initial.copy(phase = RunPhase.SUCCEEDED, terminalEvidence = TerminalEvidence(true, 1))))
        assertTrue(RunProjection.occupied(initial.copy(phase = RunPhase.OUTCOME_UNKNOWN)))
    }
}
