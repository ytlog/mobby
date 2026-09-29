package com.github.ytlog.mobby.android.runtime.api

/** Shared interpretation of authoritative runtime evidence for storage, scheduling and UI. */
object RunStateRules {
    fun occupiesExecution(snapshot: RunSnapshot) = !snapshot.phase.terminal || snapshot.phase == RunPhase.OUTCOME_UNKNOWN && snapshot.terminalEvidence?.terminationConfirmed != true
    fun displayedPhase(snapshot: RunSnapshot): RunPhase {
        val evidence = snapshot.terminalEvidence
        val exitedCleanly = evidence?.exitCode == 0
        val retained = evidence?.exitCode == null && evidence?.terminationConfirmed == true
        val confirmed = evidence?.protocolSucceeded == true && evidence.terminationConfirmed && (exitedCleanly || retained)
        return if (snapshot.phase == RunPhase.SUCCEEDED && !confirmed) RunPhase.OUTCOME_UNKNOWN else snapshot.phase
    }
}
