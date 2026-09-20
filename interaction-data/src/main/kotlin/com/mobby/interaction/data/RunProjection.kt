package com.mobby.interaction.data

import com.mobby.runtime.api.*

/** Data-owned projection. Gaps trigger snapshot replacement, never partial out-of-order append. */
internal object RunProjection {
    fun apply(current: RunSnapshot, event: EventEnvelope): RunSnapshot? {
        if (event.runId != current.runId || event.apiMajor != 1) return null
        if (event.sequence <= current.lastSequence) return current
        if (event.sequence != current.lastSequence + 1) return null
        val next = when (val payload = event.payload) {
            is RuntimeEvent.RunAccepted -> current.copy(acceptedConfig = payload.config)
            is RuntimeEvent.RunStarted -> current.copy(sessionRef = payload.sessionRef ?: current.sessionRef,
                phase = if (current.phase == RunPhase.CANCELLING || current.phase.terminal) current.phase else RunPhase.RUNNING)
            is RuntimeEvent.ProgressSummary -> current.copy(progressSummary = payload.text)
            is RuntimeEvent.AssistantDelta -> current.copy(outputSegments = current.outputSegments + payload.segment)
            is RuntimeEvent.AssistantCompleted -> current
            is RuntimeEvent.ToolStarted -> current.copy(steps = current.steps.filterNot { it.stepId == payload.stepId } + ToolSnapshot(payload.stepId, payload.toolKind, payload.summary))
            is RuntimeEvent.ToolOutput -> current.copy(steps = current.steps.map { if (it.stepId == payload.stepId) it.copy(output = it.output + payload.segment) else it })
            is RuntimeEvent.ToolFinished -> current.copy(steps = current.steps.map { if (it.stepId == payload.stepId) it.copy(outcome = payload.outcome) else it })
            is RuntimeEvent.ApprovalRequired -> current.copy(phase = RunPhase.AWAITING_APPROVAL, pendingApprovals = current.pendingApprovals.filterNot { it.approvalId == payload.approval.approvalId } + payload.approval)
            is RuntimeEvent.ApprovalResolved -> current.copy(phase = RunPhase.RUNNING, pendingApprovals = current.pendingApprovals.filterNot { it.approvalId == payload.approvalId })
            is RuntimeEvent.ArtifactAvailable -> current.copy(artifacts = current.artifacts + payload.ref)
            RuntimeEvent.CancellationRequested -> current.copy(phase = RunPhase.CANCELLING)
            is RuntimeEvent.RunFinished -> if (current.phase.terminal) current else current.copy(phase = payload.phase, terminalEvidence = payload.evidence, pendingApprovals = emptyList())
            is RuntimeEvent.Unknown -> payload.diagnosticRef?.let { current.copy(outputSegments = current.outputSegments + OutputSegment("diagnostic:${payload.kind}", event.sequence, it)) } ?: current
        }
        return next.copy(lastSequence = event.sequence, revision = current.revision + 1)
    }
    fun occupied(snapshot: RunSnapshot) = !snapshot.phase.terminal || snapshot.phase == RunPhase.OUTCOME_UNKNOWN && snapshot.terminalEvidence?.terminationConfirmed != true
    fun verifiedPhase(snapshot: RunSnapshot): RunPhase = if (snapshot.phase == RunPhase.SUCCEEDED &&
        (snapshot.terminalEvidence?.protocolSucceeded != true || snapshot.terminalEvidence?.exitCode != 0 || snapshot.terminalEvidence?.terminationConfirmed != true)) RunPhase.OUTCOME_UNKNOWN else snapshot.phase
}
