package com.github.ytlog.mobby.android.conversation.data.management

import com.github.ytlog.mobby.android.conversation.data.*
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.conversation.domain.AgentId as DomainAgent
import com.github.ytlog.mobby.android.runtime.api.AgentId as RuntimeAgent
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
        fun List<OutputSegment>.messages(separator: String = "", collapseClaudeEchoes: Boolean = false): List<Message> {
            val groups = sortedBy { it.chunkIndex }.groupBy { it.messageId }.values.toList()
            return buildList {
                groups.forEachIndexed { index, parts ->
                    val first = parts.first()
                    val text = parts.renderSegments(separator)
                    val previous = groups.getOrNull(index - 1)
                    // Old Claude runs could persist a compact snapshot as a second reply at index zero.
                    val compactEcho = collapseClaudeEchoes && parts.size == 1 && first.messageId.startsWith("msg_") &&
                        '#' !in first.messageId && previous != null && previous.last().chunkIndex + 1 == first.chunkIndex &&
                        previous.first().messageId.substringBeforeLast('#') == first.messageId &&
                        previous.first().messageId.substringAfterLast('#').toIntOrNull()?.let { it > 0 } == true &&
                        text.isNotBlank() && lastOrNull()?.text == text &&
                        content[first.ref.value]?.expired == false && previous.all { content[it.ref.value]?.expired == false }
                    if (!compactEcho) add(Message(first.messageId, text, first.chunkIndex))
                }
            }
        }
        val pendingOrder = ((snapshot?.outputSegments.orEmpty() + snapshot?.steps.orEmpty().flatMap { it.output }).maxOfOrNull { it.chunkIndex } ?: -1L) + 1
        var nextOrder = pendingOrder
        Turn(TurnId(id), userText, runId?.let(::ExecutionId), snapshot?.let { RunStateRules.displayedPhase(it).domain() },
            snapshot?.outputSegments?.filterNot { it.messageId.startsWith("diagnostic:") }
                ?.messages(collapseClaudeEchoes = snapshot.acceptedConfig.agentId == RuntimeAgent.CLAUDE_CODE).orEmpty() +
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
            // Diagnostics such as stderr are lines; their segments are joined the way the runtime merged them.
            snapshot?.outputSegments?.filter { it.messageId.startsWith("diagnostic:") }?.messages("\n").orEmpty(),
            when (error) {
                OutputCache.VERIFICATION_WARNING -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.cannotVerifyHistoricalOutputYetCachePreservedReconnectAnd).joinToString("\n")
                AppStrings.OUTPUT_SYNC_MARKER -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.outputSyncRetrying).joinToString("\n")
                AppStrings.STATE_SYNC_MARKER -> listOfNotNull(snapshot?.terminalEvidence?.error?.message(), AppStrings.stateSyncRetrying).joinToString("\n")
                else -> error ?: snapshot?.terminalEvidence?.error?.message()
            }, snapshot?.progress?.domain() ?: snapshot?.takeIf { endedWithoutReply(it, content) }?.let { com.github.ytlog.mobby.android.conversation.domain.ProgressNotice.NO_REPLY }, pending, occupied, queued && !pending, expanded, storageJson.decodeFromString(expandedSteps),
            snapshot?.artifacts?.mapNotNull { ref -> content[ref.value]?.takeUnless { it.expired }?.let { SkillProposal(ref.value, it.text, DomainAgent.valueOf(snapshot.acceptedConfig.agentId.name)) } }.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).creator != null, snapshot?.artifacts?.any { it.value !in content } == true, storageJson.decodeFromString<StoredConversation>(frozen).attachments,
            snapshot?.pendingApprovals?.map { PermissionRequest(it.approvalId, it.revision, it.subject.domain()) }.orEmpty(), snapshot?.deviceOperations.orEmpty(),
            storageJson.decodeFromString<StoredConversation>(frozen).workspace, storageJson.decodeFromString<StoredConversation>(frozen).project)
    }
}

/**
 * A run that succeeded with neither reply text nor a skill draft: the agent stopped without answering. Reply output counts
 * as none only once it is here and blank, such as Pi's newlines between tool calls; output still syncing or expired does not.
 */
private fun endedWithoutReply(snapshot: RunSnapshot, content: Map<String, ChunkRow>) = snapshot.phase == RunPhase.SUCCEEDED &&
    snapshot.artifacts.isEmpty() && snapshot.outputSegments.filterNot { it.messageId.startsWith("diagnostic:") }
        .all { segment -> content[segment.ref.value]?.let { !it.expired && it.text.isBlank() } == true }

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
