package com.github.ytlog.mobby.android.interaction.data

import com.github.ytlog.mobby.android.runtime.api.*

/** Data-owned projection. Gaps trigger snapshot replacement, never partial out-of-order append. */
internal object RunProjection {
    fun apply(current: RunSnapshot, event: EventEnvelope): RunSnapshot? {
        if (event.runId != current.runId || event.apiMajor != 1) return null
        if (event.sequence <= current.lastSequence) return current
        if (event.sequence != current.lastSequence + 1) return null
        val next = when (val payload = event.payload) {
            is RuntimeEvent.RunAccepted -> current.copy(acceptedConfig = payload.config)
            is RuntimeEvent.RunStarted -> current.copy(sessionRef = payload.sessionRef ?: current.sessionRef,
                phase = if (current.phase == RunPhase.CANCELLING || current.phase.terminal || current.pendingApprovals.isNotEmpty()) current.phase else RunPhase.RUNNING)
            is RuntimeEvent.ProgressSummary -> current.copy(progressSummary = payload.text)
            is RuntimeEvent.AssistantDelta -> current.copy(outputSegments = current.outputSegments + payload.segment)
            is RuntimeEvent.AssistantCompleted -> current
            is RuntimeEvent.ToolStarted -> {
                val previous = current.steps.firstOrNull { it.stepId == payload.stepId }
                val order = if (payload.order >= 0) payload.order else previous?.order ?: -1
                val tool = ToolSnapshot(payload.stepId, payload.body, previous?.outcome, previous?.output ?: emptyList(), order)
                current.copy(steps = current.steps.filterNot { it.stepId == payload.stepId } + tool)
            }
            is RuntimeEvent.ToolOutput -> current.copy(steps = current.steps.map { if (it.stepId == payload.stepId) it.copy(output = it.output + payload.segment) else it })
            is RuntimeEvent.ToolFinished -> current.copy(steps = current.steps.map { if (it.stepId == payload.stepId) it.copy(outcome = payload.outcome) else it })
            is RuntimeEvent.ApprovalRequired -> current.copy(phase = RunPhase.AWAITING_APPROVAL, pendingApprovals = current.pendingApprovals.filterNot { it.approvalId == payload.approval.approvalId } + payload.approval)
            is RuntimeEvent.ApprovalResolved -> {
                val remaining = current.pendingApprovals.filterNot { it.approvalId == payload.approvalId }
                current.copy(phase = if (remaining.isEmpty()) RunPhase.RUNNING else RunPhase.AWAITING_APPROVAL, pendingApprovals = remaining)
            }
            is RuntimeEvent.ArtifactAvailable -> current.copy(artifacts = current.artifacts + payload.ref)
            RuntimeEvent.CancellationRequested -> current.copy(phase = RunPhase.CANCELLING, pendingApprovals = emptyList())
            is RuntimeEvent.RunFinished -> if (current.phase.terminal) current else current.copy(phase = payload.phase, terminalEvidence = payload.evidence, pendingApprovals = emptyList())
            is RuntimeEvent.Unknown -> payload.diagnosticRef?.let { current.copy(outputSegments = current.outputSegments + OutputSegment("diagnostic:${payload.kind}", event.sequence, it)) } ?: current
        }
        return next.copy(lastSequence = event.sequence, revision = current.revision + 1)
    }
    fun occupied(snapshot: RunSnapshot) = !snapshot.phase.terminal || snapshot.phase == RunPhase.OUTCOME_UNKNOWN && snapshot.terminalEvidence?.terminationConfirmed != true
    fun verifiedPhase(snapshot: RunSnapshot): RunPhase {
        val evidence = snapshot.terminalEvidence
        val exitedCleanly = evidence?.exitCode == 0
        val retained = evidence?.exitCode == null && evidence?.terminationConfirmed == true
        val confirmed = evidence?.protocolSucceeded == true && evidence.terminationConfirmed && (exitedCleanly || retained)
        return if (snapshot.phase == RunPhase.SUCCEEDED && !confirmed) RunPhase.OUTCOME_UNKNOWN else snapshot.phase
    }
}
