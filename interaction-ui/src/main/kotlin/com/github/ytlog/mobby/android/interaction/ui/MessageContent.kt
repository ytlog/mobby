package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.domain.*

private val LocalToolCodeActionScale = staticCompositionLocalOf { 1f }

internal fun ProgressNotice.label() = when (this) {
    ProgressNotice.OUTPUT_TRUNCATED -> AppStrings.outputExceedsTheRetentionLimitSubsequentContentWasTruncated
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
internal fun Turn.hasVisibleExecution(): Boolean = steps.isNotEmpty()
/** Routine CLI logs stay stored. The action appears only when this turn did not finish normally. */
internal fun Turn.diagnosticsActionVisible(): Boolean = diagnostics.isNotEmpty() && (failure != null || phase in setOf(
    ExecutionPhase.FAILED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN,
))
internal fun toolGroupKey(steps: List<Step>) = "tools:${steps.first().id}"
/** The tool list stays open for the whole turn. It collapses only after execution has finished. */
internal fun Turn.toolsLive(): Boolean = occupied && phase in setOf(
    null, ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL, ExecutionPhase.CANCELLING,
)
internal fun Turn.toolGroupExpanded(steps: List<Step>): Boolean = toolsLive() || toolGroupKey(steps) in expandedSteps
internal fun Turn.executionHeadline(steps: List<Step> = this.steps): String {
    val count = steps.size
    return when {
        phase == ExecutionPhase.CANCELLING -> AppStrings.stopping2
        toolsLive() -> AppStrings.running
        phase == ExecutionPhase.SUCCEEDED || phase == ExecutionPhase.RUNNING || phase == ExecutionPhase.ACCEPTED || phase == null ->
            if (steps.isNotEmpty() && steps.all { it is Step.Thinking }) AppStrings.thought else AppStrings.completedSteps(count)
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
    "thinking" -> AppIcons.Help
    "todo_list", "task" -> AppIcons.Skill
    "mcp_tool_call", "tool", "snapshot", "click", "type", "tap", "back", "home", "recents" -> AppIcons.Phone
    "mcp__phone__snapshot", "mcp__phone__click", "mcp__phone__type", "mcp__phone__tap",
    "mcp__phone__back", "mcp__phone__home", "mcp__phone__recents" -> AppIcons.Phone
    else -> AppIcons.More
}
@Composable internal fun CodeContent(title: String, text: String, read: (String, String) -> Unit, container: Color = raisedColor(), ink: Color = Color.Unspecified) {
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(false) }
    val content = if (ink == Color.Unspecified) LocalContentColor.current else ink
    val titleInk = if (ink == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else ink
    Surface(shape = RoundedCornerShape(16.dp), color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.take(30), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = titleInk)
                val actionTint = if (darkChrome()) content else Color.Unspecified
                val actionScale = LocalToolCodeActionScale.current
                ActionIcon(AppStrings.copyOriginalCode, { clipboard.setText(AnnotatedString(text)) }, AppIcons.Copy, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
                ActionIcon(if (wrap) AppStrings.disableCodeWrapping else AppStrings.wrapCode, { wrap = !wrap }, AppIcons.Wrap, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
                ActionIcon(AppStrings.expandCode, { read(title, text) }, AppIcons.Expand, tint = actionTint, buttonSize = ToolbarControl * actionScale, glyphSize = 22.dp * actionScale)
            }
            SelectionContainer {
                Text(text, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = wrap)
            }
        }
    }
}
@Composable internal fun ExecutionFlow(turn: Turn, vm: ConversationViewModel, steps: List<Step> = turn.steps, showExtras: Boolean = true, read: (String, String) -> Unit) {
    if (steps.isEmpty()) return
    val expanded = turn.toolGroupExpanded(steps)
    val ink = toolCallInk()
    CompositionLocalProvider(LocalToolCodeActionScale provides 2f / 3f) {
        Column(Modifier.fillMaxWidth().testTag("execution-flow")) {
            Row(
                Modifier.fillMaxWidth().clickable { vm.enqueue { vm.actions.stepExpansion(turn.id, toolGroupKey(steps), !expanded) } }
                    .heightIn(min = 44.dp).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(turn.executionHeadline(steps), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ink)
                AppIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronRight, if (expanded) AppStrings.expanded else AppStrings.collapsed, Modifier.size(18.dp), tint = ink)
            }
            if (expanded) Column(Modifier.padding(start = 2.dp, end = 8.dp, bottom = 8.dp)) {
                if (showExtras) turn.progress?.let { Text(it.label(), Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = ink) }
                steps.forEach { step -> key(step.id) {
                    val open = step.id in turn.expandedSteps
                    val view = remember(step, LanguagePreferences.current) { ToolPresentation.present(step) }
                    val liveThought = turn.occupied && step.id == steps.lastOrNull { it is Step.Thinking && it.outcome == null }?.id
                    Row(Modifier.fillMaxWidth().clickable {
                        val next = !open
                        vm.enqueue {
                            vm.actions.stepExpansion(turn.id, step.id, next)
                            if (next) vm.actions.expansion(turn.id, true)
                        }
                    }.heightIn(min = 40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepGlyph(step.glyphKind(), step.outcome, turn.phase)
                        Text(
                            view.title,
                            Modifier.weight(1f).padding(horizontal = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = ink,
                        )
                        AppIcon(if (open) AppIcons.ChevronDown else AppIcons.ChevronRight, if (open) AppStrings.expanded else AppStrings.collapsed, Modifier.size(16.dp), tint = ink)
                    }
                    if (open) {
                        val body = view.detail
                        Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp, bottom = 8.dp)) {
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
                } }
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
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = raisedColor()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    AppIcon(icon, stepStatusLabel(outcome, phase), Modifier.size(18.dp), tint = when (outcome) {
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> toolCallInk()
    })
}

@Composable internal fun StepStatus(outcome: String?, phase: ExecutionPhase?) {
    StepGlyph("", outcome, phase)
}
