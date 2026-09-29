package com.github.ytlog.mobby.android.runtime.api

import org.junit.Assert.*
import org.junit.Test

class RunStateRulesTest {
    private val config = RunConfigSnapshot(AgentId.CODEX, WorkspaceRef("fixture"), "model", null, GatewayProfileRef("fixture", 0), emptySet())
    private fun state(phase: RunPhase, evidence: TerminalEvidence? = null) = RunSnapshot(RunId("fixture"), phase, 1, 1, config, terminalEvidence = evidence)

    @Test fun `unknown outcome blocks new work only until process termination is confirmed`() {
        assertTrue(RunStateRules.occupiesExecution(state(RunPhase.OUTCOME_UNKNOWN)))
        assertTrue(RunStateRules.occupiesExecution(state(RunPhase.OUTCOME_UNKNOWN, TerminalEvidence(null, null))))
        for (phase in RunPhase.values().filter { !it.terminal }) assertTrue(RunStateRules.occupiesExecution(state(phase)))
        val recovered = state(RunPhase.OUTCOME_UNKNOWN, TerminalEvidence(null, null, terminationConfirmed = true))
        assertFalse(RunStateRules.occupiesExecution(recovered))
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunStateRules.displayedPhase(recovered))
    }
    @Test fun `a success label requires protocol and process evidence while cancellation remains cancellation`() {
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunStateRules.displayedPhase(state(RunPhase.SUCCEEDED, TerminalEvidence(true, null))))
        assertEquals(RunPhase.OUTCOME_UNKNOWN, RunStateRules.displayedPhase(state(RunPhase.SUCCEEDED, TerminalEvidence(null, 0))))
        assertEquals(RunPhase.SUCCEEDED, RunStateRules.displayedPhase(state(RunPhase.SUCCEEDED, TerminalEvidence(true, 0))))
        assertEquals(RunPhase.SUCCEEDED, RunStateRules.displayedPhase(state(RunPhase.SUCCEEDED, TerminalEvidence(true, null, terminationConfirmed = true))))
        val cancelled = state(RunPhase.CANCELLED, TerminalEvidence(null, 143))
        assertEquals(RunPhase.CANCELLED, RunStateRules.displayedPhase(cancelled))
        assertFalse(RunStateRules.occupiesExecution(cancelled))
    }
}
