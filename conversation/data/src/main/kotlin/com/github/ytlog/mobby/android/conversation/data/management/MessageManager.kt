package com.github.ytlog.mobby.android.conversation.data.management

import com.github.ytlog.mobby.android.conversation.data.*
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.conversation.domain.AgentId as DomainAgent
import com.github.ytlog.mobby.android.localization.AppStrings

/** Renders persisted turn output and preserves the directory recorded when each message was sent. */
internal class MessageManager {
    fun render(row: TurnRow, content: Map<String, ChunkRow>): Turn = row.run {
        val snapshot = snapshot?.let { storageJson.decodeFromString<RunSnapshot>(it) }
        fun List<OutputSegment>.renderSegments(separator: String = ""): String = buildString {
            var previousExpired = false
            for (part in this@renderSegments) {
                val row = content[part.ref.value] ?: continue
                if (row.expired && previousExpired) continue
                if (isNotEmpty()) append(if (row.expired || previousExpired) "\n" else separator)
                append(if (row.expired) AppStrings.outputWasCleanedUpByTheRetentionPolicy else row.text)
                previousExpired = row.expired
            }
        }
        fun List<OutputSegment>.messages() = sortedBy { it.chunkIndex }.groupBy { it.messageId }.map { (id, parts) -> Message(id, parts.renderSegments(), parts.minOf { it.chunkIndex }) }
        val pendingOrder = ((snapshot?.outputSegments.orEmpty() + snapshot?.steps.orEmpty().flatMap { it.output }).maxOfOrNull { it.chunkIndex } ?: -1L) + 1
        var nextOrder = pendingOrder
        Turn(TurnId(id), userText, runId?.let(::ExecutionId), snapshot?.let { RunStateRules.displayedPhase(it).domain() },
            snapshot?.outputSegments?.filterNot { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty() +
                if (snapshot?.artifacts?.any { content[it.value]?.expired == true } == true) listOf(Message("retained-artifact-notice", AppStrings.skillDraftWasCleanedUpByTheRetentionPolicy)) else emptyList(),
            snapshot?.steps?.map { step ->
                val order = if (step.order >= 0) step.order else step.output.minOfOrNull { it.chunkIndex } ?: nextOrder++
                val text = step.output.sortedBy { it.chunkIndex }.renderSegments(if (step.body is StepBody.Thinking) "" else "\n")
                val outcome = step.outcome?.name
                when (val body = step.body) {
                    StepBody.Thinking -> Step.Thinking(step.stepId, text, outcome, order)
                    is StepBody.Command -> Step.Command(step.stepId, body.command, text, outcome, order)
                    is StepBody.FileRead -> Step.FileRead(step.stepId, body.path, text, outcome, order)
                    is StepBody.FileWrite -> Step.FileWrite(step.stepId, body.path, text, outcome, order)
                    is StepBody.FileDiff -> Step.FileDiff(step.stepId, body.paths, text, outcome, order)
                    is StepBody.Action -> Step.Action(step.stepId, body.name, body.detail, text, outcome, order)
                }
            }.orEmpty(),
            snapshot?.outputSegments?.filter { it.messageId.startsWith("diagnostic:") }?.messages().orEmpty(),
            when (error) {
                OutputCache.VERIFICATION_WARNING -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.cannotVerifyHistoricalOutputYetCachePreservedReconnectAnd).joinToString("\n")
                AppStrings.OUTPUT_SYNC_MARKER -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.outputSyncRetrying).joinToString("\n")
                AppStrings.STATE_SYNC_MARKER -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.stateSyncRetrying).joinToString("\n")
                else -> error ?: snapshot?.terminalEvidence?.error?.message()
            }, snapshot?.progress?.domain(), pending, occupied, queued && !pending, expanded, storageJson.decodeFromString(expandedSteps),
            snapshot?.artifacts?.mapNotNull { ref -> content[ref.value]?.takeUnless { it.expired }?.let { SkillProposal(ref.value, it.text, DomainAgent.valueOf(snapshot.acceptedConfig.agentId.name)) } }.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).creator != null, snapshot?.artifacts?.any { it.value !in content } == true, storageJson.decodeFromString<StoredConversation>(frozen).attachments,
            snapshot?.pendingApprovals?.map { PermissionRequest(it.approvalId, it.revision, it.subject.domain()) }.orEmpty(), snapshot?.deviceOperations.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).workspace, storageJson.decodeFromString<StoredConversation>(frozen).project)
    }
}

private fun com.github.ytlog.mobby.android.runtime.api.ProgressNotice.domain() = when (this) {
    com.github.ytlog.mobby.android.runtime.api.ProgressNotice.OUTPUT_TRUNCATED ->
        com.github.ytlog.mobby.android.conversation.domain.ProgressNotice.OUTPUT_TRUNCATED
}

private fun ApprovalSubject.domain(): PermissionSubject = when (this) {
    is ApprovalSubject.Command -> PermissionSubject.Command(command)
    is ApprovalSubject.FileRead -> PermissionSubject.FileRead(path, offset, limit)
    is ApprovalSubject.FileWrite -> PermissionSubject.FileWrite(path, content)
    is ApprovalSubject.FileDiff -> PermissionSubject.FileDiff(paths, diff)
    is ApprovalSubject.Action -> PermissionSubject.Action(name, detail)
}
