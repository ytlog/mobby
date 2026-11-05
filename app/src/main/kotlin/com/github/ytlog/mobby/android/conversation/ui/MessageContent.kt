package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.deviceoperation.ui.showsFoldedPreview
import com.github.ytlog.mobby.android.runtime.api.device.DeviceRecord
import com.github.ytlog.mobby.android.runtime.api.device.DeviceStatus

private val LocalToolCodeActionScale = staticCompositionLocalOf { 0.82f }

internal fun ProgressNotice.label() = when (this) {
    ProgressNotice.OUTPUT_TRUNCATED -> AppStrings.outputExceedsTheRetentionLimitSubsequentContentWasTruncated
    ProgressNotice.NO_REPLY -> AppStrings.agentEndedWithoutReply
}

/** A turn's notice, shown under the turn: the step group starts collapsed and would hide it. */
@Composable internal fun TurnNotice(notice: ProgressNotice) {
    Text(notice.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun AgentId.label() = when (this) {
    AgentId.PI -> AppStrings.piName
    AgentId.CODEX -> AppStrings.codexName
    AgentId.CLAUDE_CODE -> AppStrings.claudeCodeName
    AgentId.OPEN_CODE -> AppStrings.openCodeName
}

internal fun AgentId.glyph() = when (this) {
    AgentId.PI -> AppIcons.Pi
    AgentId.CODEX -> AppIcons.Codex
    AgentId.CLAUDE_CODE -> AppIcons.Claude
    AgentId.OPEN_CODE -> AppIcons.OpenCode
}
internal fun ExecutionPhase?.label(): String = when (this) {
    null -> AppStrings.awaitingAcceptance
    ExecutionPhase.ACCEPTED -> AppStrings.preparing
    ExecutionPhase.RUNNING -> AppStrings.running
    ExecutionPhase.AWAITING_APPROVAL -> AppStrings.pendingConfirmation
    ExecutionPhase.CANCELLING -> AppStrings.stopping2
    ExecutionPhase.SUCCEEDED -> AppStrings.done
    ExecutionPhase.FAILED -> AppStrings.failed
    ExecutionPhase.CANCELLED -> AppStrings.stopped
    ExecutionPhase.TIMED_OUT -> AppStrings.timedOut
    ExecutionPhase.INTERRUPTED -> AppStrings.interrupted
    ExecutionPhase.OUTCOME_UNKNOWN -> AppStrings.resultUnconfirmed
}
/**
 * What the timeline draws for a turn. Finished work between two replies folds into one [Process] line, so replies stay
 * the main thing on the page. An operation that is running, wants an answer, did not plainly succeed or has a photo
 * to show stays a [Device] entry of its own: folding it would hide something the reader needs.
 */
internal sealed interface TimelineEntry {
    data class Reply(val message: Message) : TimelineEntry
    data class Device(val record: DeviceRecord) : TimelineEntry
    data class Process(val steps: List<Step>, val devices: List<DeviceRecord> = emptyList()) : TimelineEntry
}

private fun DeviceRecord.folds() = operation.status == DeviceStatus.SUCCEEDED && operation.requiresAttention == null && !operation.showsFoldedPreview()

internal fun Turn.timelineEntries(): List<TimelineEntry> {
    val result = mutableListOf<TimelineEntry>()
    val steps = mutableListOf<Step>()
    val devices = mutableListOf<DeviceRecord>()
    fun flush() {
        if (steps.isNotEmpty() || devices.isNotEmpty()) result += TimelineEntry.Process(steps.toList(), devices.toList())
        steps.clear(); devices.clear()
    }
    transcript().forEach { entry -> when (entry) {
        is TranscriptEntry.Reply -> { flush(); result += TimelineEntry.Reply(entry.message) }
        is TranscriptEntry.ToolRun -> steps += entry.steps
        is TranscriptEntry.Device -> if (entry.record.folds()) devices += entry.record else { flush(); result += TimelineEntry.Device(entry.record) }
    } }
    flush()
    return result
}

internal fun TimelineEntry.itemKey(turn: Turn): String = when (this) {
    is TimelineEntry.Reply -> "message:${turn.id.value}:${message.id}"
    is TimelineEntry.Device -> "device:${turn.id.value}:${record.operation.operationId}"
    is TimelineEntry.Process -> steps.firstOrNull()?.let { "tools:${turn.id.value}:${it.id}" }
        ?: "device:${turn.id.value}:${devices.first().operation.operationId}"
}

/** Saved reading positions and search hits can name a row that now sits inside a summary. */
internal fun Turn.timelineKeyAliases(): Map<String, String> = timelineEntries().filterIsInstance<TimelineEntry.Process>().flatMap { entry ->
    val summary = entry.itemKey(this)
    entry.steps.map { "tools:${id.value}:${it.id}" to summary } + entry.devices.map { "device:${id.value}:${it.operation.operationId}" to summary }
}.toMap()

/** The turn draws its own status row, so a step group need not repeat how the run ended. */
internal fun Turn.reportsOutcome(): Boolean = queued || failure != null || phase in setOf(
    ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN, ExecutionPhase.AWAITING_APPROVAL,
)

internal fun Turn.hasVisibleExecution(): Boolean = steps.isNotEmpty()
/** Routine CLI logs stay stored. The action appears only when this turn did not finish normally. */
internal fun Turn.diagnosticsActionVisible(): Boolean = diagnostics.isNotEmpty() && (failure != null || phase in setOf(
    ExecutionPhase.FAILED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN,
))
internal fun toolGroupKey(steps: List<Step>) = "tools:${steps.first().id}"
/** The turn is still running; its current step group stays open. */
internal fun Turn.toolsLive(): Boolean = occupied && phase in setOf(
    null, ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL, ExecutionPhase.CANCELLING,
)
private fun Turn.endsWith(steps: List<Step>) = steps.isNotEmpty() && steps.last().id == this.steps.sortedBy { it.order }.lastOrNull()?.id
internal fun Turn.toolGroupExpanded(steps: List<Step>): Boolean = (toolsLive() && endsWith(steps)) || toolGroupKey(steps) in expandedSteps
internal fun Turn.executionHeadline(steps: List<Step> = this.steps, count: Int = steps.size): String {
    val ended = endsWith(steps)
    val failedSteps = steps.count { it.outcome == "FAILED" }
    val done = if (steps.isNotEmpty() && count == steps.size && steps.all { it is Step.Thinking }) AppStrings.thought else AppStrings.completedSteps(count)
    return when {
        ended && phase == ExecutionPhase.CANCELLING -> AppStrings.stopping2
        ended && toolsLive() -> AppStrings.running
        failedSteps == count -> AppStrings.steps(ExecutionPhase.FAILED.label(), count)
        failedSteps > 0 -> AppStrings.stepsPartlyFailed(count, failedSteps)
        // A failed run with no message has no status row, so its last group has to say so.
        ended && phase == ExecutionPhase.FAILED && failure == null -> AppStrings.steps(phase.label(), count)
        steps.all { it.outcome == "SUCCEEDED" } -> done
        // Unresolved steps claim nothing here; the status row says how the run ended.
        reportsOutcome() -> AppStrings.stepCount(count)
        phase == ExecutionPhase.SUCCEEDED || phase == ExecutionPhase.RUNNING || phase == ExecutionPhase.ACCEPTED || phase == null -> done
        else -> AppStrings.steps(phase.label(), count)
    }
}
internal fun Step.glyphKind(): String = when (this) {
    is Step.Thinking -> "thinking"
    is Step.Command -> "command_execution"
    is Step.FileRead -> "read"
    is Step.FileWrite -> "write"
    is Step.FileDiff -> "file_change"
    is Step.Action -> name
}
internal fun stepKindIcon(kind: String): AppGlyph = when (kind.lowercase()) {
    "websearch", "web_search", "grep", "glob" -> AppIcons.Search
    "webfetch" -> AppIcons.Globe
    "read", "write", "edit", "editnotebook", "ls", "file_change" -> AppIcons.File
    "bash", "shell", "command_execution" -> AppIcons.Terminal
    "thinking" -> AppIcons.Thought
    "todo_list", "task" -> AppIcons.Skill
    "mcp_tool_call", "tool", "snapshot", "click", "type", "tap", "back", "home", "recents" -> AppIcons.Phone
    "mcp__phone__snapshot", "mcp__phone__click", "mcp__phone__type", "mcp__phone__tap",
    "mcp__phone__back", "mcp__phone__home", "mcp__phone__recents" -> AppIcons.Phone
    else -> AppIcons.More
}
internal val ProcessRowHeight = 36.dp
internal val ProcessTextSize = 13.sp

/** Secondary lines of a run: small enough to sit clearly below the reply text they accompany. */
@Composable internal fun processTextStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = ProcessTextSize, lineHeight = 18.sp)

/** One quiet line of a run, aligned with the reply text: what happened, how it went, and a way to open it. */
@Composable internal fun ProcessRow(
    title: String,
    leading: (@Composable () -> Unit)?,
    expanded: Boolean?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusColor: Color = Color.Unspecified,
    style: TextStyle = processTextStyle(),
) {
    val ink = toolCallInk()
    Row(
        modifier.fillMaxWidth().heightIn(min = ProcessRowHeight).clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = ink)
        if (status != null) {
            Spacer(Modifier.width(8.dp))
            Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, color = if (statusColor == Color.Unspecified) ink else statusColor)
        }
        if (expanded != null) {
            Spacer(Modifier.width(2.dp))
            AppIcon(if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight, if (expanded) AppStrings.expanded else AppStrings.collapsed, Modifier.size(12.dp), tint = ink)
        }
    }
}

@Composable internal fun CodeContent(title: String, text: String, read: (String, String) -> Unit, container: Color = conversationCardColor(), ink: Color = Color.Unspecified) {
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(false) }
    val content = if (ink == Color.Unspecified) LocalContentColor.current else ink
    val titleInk = if (ink == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else ink
    Surface(shape = RoundedCornerShape(12.dp), color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.take(30), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = titleInk)
                val actionTint = if (darkChrome()) content else Color.Unspecified
                val actionScale = LocalToolCodeActionScale.current
                ActionIcon(AppStrings.copyOriginalCode, { clipboard.setText(AnnotatedString(text)) }, AppIcons.Copy, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
                ActionIcon(if (wrap) AppStrings.disableCodeWrapping else AppStrings.wrapCode, { wrap = !wrap }, AppIcons.Wrap, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
                ActionIcon(AppStrings.expandCode, { read(title, text) }, AppIcons.Expand, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
            }
            SelectionContainer {
                // Markdown code literals end in a newline; copying keeps it, the block does not draw an empty last line.
                Text(text.trimEnd('\n'), modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = wrap)
            }
        }
    }
}
@Composable private fun StepRow(step: Step, turn: Turn, group: List<Step>, vm: ConversationViewModel, read: (String, String) -> Unit) {
    val ink = toolCallInk()
    val open = step.id in turn.expandedSteps
    val view = remember(step, LanguagePreferences.current) { ToolPresentation.present(step) }
    val liveThought = turn.occupied && step.id == group.lastOrNull { it is Step.Thinking && it.outcome == null }?.id
    ProcessRow(
        view.title,
        leading = { StepGlyph(step.glyphKind(), step.outcome, turn.phase) },
        expanded = open,
        onClick = {
            val next = !open
            vm.enqueue {
                vm.actions.stepExpansion(turn.id, step.id, next)
                if (next) vm.actions.expansion(turn.id, true)
            }
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (open) {
        val body = view.detail
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 8.dp)) {
            when {
                step is Step.Thinking -> if (body.isBlank()) {
                    if (!liveThought) Text(AppStrings.noOutput, style = MaterialTheme.typography.bodySmall, color = ink)
                } else ReplyContent(body, streaming = liveThought, read = read)
                view.terminal -> CodeContent(view.title, body.ifBlank { AppStrings.noOutput }, read, toolCallSurface(), ink)
                ToolPresentation.looksLikeMarkdown(body) -> ReplyContent(body, streaming = false, read = read)
                else -> SelectionContainer {
                    Text(body.ifBlank { AppStrings.noOutput }, style = MaterialTheme.typography.bodySmall, color = ink,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}

/** One line summing up finished work; opening it lists the steps and operations in the order they happened. */
@Composable internal fun ExecutionFlow(turn: Turn, vm: ConversationViewModel, steps: List<Step> = turn.steps, showExtras: Boolean = true,
    devices: List<DeviceRecord> = emptyList(), deviceRow: @Composable (DeviceRecord) -> Unit = {}, read: (String, String) -> Unit) {
    if (steps.isEmpty() && devices.isEmpty()) return
    val groupKey = if (steps.isNotEmpty()) toolGroupKey(steps) else "tools:${devices.first().operation.operationId}"
    val expanded = if (steps.isNotEmpty()) turn.toolGroupExpanded(steps) else groupKey in turn.expandedSteps
    val rail = toolCallBorder()
    val failed = steps.any { it.outcome == "FAILED" }
    val error = MaterialTheme.colorScheme.error
    CompositionLocalProvider(LocalToolCodeActionScale provides 2f / 3f) {
        Column(Modifier.fillMaxWidth().testTag("execution-flow")) {
            ProcessRow(
                turn.executionHeadline(steps, steps.size + devices.size),
                leading = if (failed) ({ AppIcon(AppIcons.Error, null, Modifier.size(14.dp), tint = error) }) else null,
                expanded = expanded,
                onClick = { vm.enqueue { vm.actions.stepExpansion(turn.id, groupKey, !expanded) } },
            )
            if (expanded) Column(Modifier.fillMaxWidth().drawBehind {
                drawLine(rail, Offset(0.5.dp.toPx(), 0f), Offset(0.5.dp.toPx(), size.height), 1.dp.toPx())
            }.padding(start = 12.dp, bottom = 4.dp)) {
                val rows: List<Pair<Long, @Composable () -> Unit>> =
                    steps.map { step -> step.order to @Composable { key(step.id) { StepRow(step, turn, steps, vm, read) } } } +
                    devices.map { record -> record.order to @Composable { key(record.operation.operationId) { deviceRow(record) } } }
                rows.sortedBy { it.first }.forEach { (_, row) -> row() }
                if (showExtras && turn.diagnosticsActionVisible()) TextButton(onClick = { read(AppStrings.runtimeDiagnostics, turn.diagnostics.joinToString("\n\n") { it.text }) }) { Text(AppStrings.viewDiagnostics(turn.diagnostics.size)) }
            }
        }
    }
}

@Composable internal fun PermissionCard(turn: Turn, permission: PermissionRequest, agent: AgentId, vm: ConversationViewModel) {
    val execution = turn.execution ?: return
    val key = PermissionKey(execution, permission.id, permission.revision)
    val status by vm.status.collectAsState()
    val agents by vm.agents.collectAsState()
    val busy by vm.permissionBusy.collectAsState()
    val submitted by vm.permissionSubmitted.collectAsState()
    val enabled = status.connected && agents.any { it.agent == agent && it.approvals } &&
        turn.phase == ExecutionPhase.AWAITING_APPROVAL && key !in busy && key !in submitted
    PermissionContent(permission, enabled, key in busy, key in submitted, status.connected) { allow -> vm.decidePermission(execution, permission, allow) }
}

@Composable internal fun PermissionContent(permission: PermissionRequest, enabled: Boolean, busy: Boolean, submitted: Boolean, connected: Boolean, decide: (Boolean) -> Unit) {
    val view = remember(permission.subject) { ToolPresentation.permission(permission.subject) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = conversationCardColor()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AppStrings.yourPermissionIsRequired, style = MaterialTheme.typography.titleMedium)
            Text(view.title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(view.detail, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            }
            if (submitted) Text(AppStrings.decisionAcceptedAwaitingExecutionResult)
            else if (busy) Text(AppStrings.submittingDecision)
            else if (!connected) Text(AppStrings.disconnectedReconnectBeforeConfirming)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { decide(false) }, enabled = enabled, colors = outlinedButtonColors()) { Text(AppStrings.deny) }
                Button(onClick = { decide(true) }, enabled = enabled, colors = filledButtonColors()) { Text(AppStrings.allowOnce) }
            }
        }
    }
}

internal fun stepStatusLabel(outcome: String?, phase: ExecutionPhase?): String = when (outcome) {
    "SUCCEEDED" -> AppStrings.stepCompleted
    "FAILED" -> AppStrings.stepFailed
    "CANCELLED" -> AppStrings.stepCancelled
    null -> when (phase) {
        ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL -> AppStrings.stepRunning
        ExecutionPhase.CANCELLING -> AppStrings.stepStopping
        else -> AppStrings.stepResultUnconfirmed
    }
    else -> AppStrings.stepResultUnconfirmed
}

@Composable internal fun StepGlyph(kind: String, outcome: String?, phase: ExecutionPhase?) {
    val icon = when (outcome) {
        "FAILED" -> AppIcons.Error
        "CANCELLED" -> AppIcons.Close
        else -> stepKindIcon(kind)
    }
    AppIcon(icon, stepStatusLabel(outcome, phase), Modifier.size(14.dp), tint = when (outcome) {
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> toolCallInk()
    })
}

@Composable internal fun StepStatus(outcome: String?, phase: ExecutionPhase?) {
    StepGlyph("", outcome, phase)
}
