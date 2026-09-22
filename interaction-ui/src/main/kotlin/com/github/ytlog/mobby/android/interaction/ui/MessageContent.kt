package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.domain.*

internal fun AgentId.label() = if (this == AgentId.CODEX) "Codex" else "Claude Code"
internal fun ExecutionPhase?.label(): String = when (this) {
    null -> "等待接纳"
    ExecutionPhase.ACCEPTED -> "准备执行"
    ExecutionPhase.RUNNING -> "执行中"
    ExecutionPhase.AWAITING_APPROVAL -> "待确认"
    ExecutionPhase.CANCELLING -> "停止中"
    ExecutionPhase.SUCCEEDED -> "完成"
    ExecutionPhase.FAILED -> "失败"
    ExecutionPhase.CANCELLED -> "已停止"
    ExecutionPhase.TIMED_OUT -> "超时"
    ExecutionPhase.INTERRUPTED -> "异常中断"
    ExecutionPhase.OUTCOME_UNKNOWN -> "结果待确认"
}
internal fun Turn.hasVisibleExecution(): Boolean = steps.isNotEmpty()
/** Routine CLI logs stay stored. The action appears only when this turn did not finish normally. */
internal fun Turn.diagnosticsActionVisible(): Boolean = diagnostics.isNotEmpty() && (failure != null || phase in setOf(
    ExecutionPhase.FAILED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN,
))
internal fun toolGroupKey(steps: List<Step>) = "tools:${steps.first().id}"
internal fun Turn.toolGroupExpanded(steps: List<Step>): Boolean {
    val busy = occupied && steps.any { it.outcome == null } &&
        phase in setOf(null, ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL, ExecutionPhase.CANCELLING)
    return busy || toolGroupKey(steps) in expandedSteps
}
internal fun Turn.executionHeadline(steps: List<Step> = this.steps): String {
    val count = steps.size
    val busy = occupied && steps.any { it.outcome == null } &&
        phase in setOf(null, ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL)
    return when {
        phase == ExecutionPhase.CANCELLING -> "停止中"
        busy -> "执行中"
        phase == ExecutionPhase.SUCCEEDED || phase == ExecutionPhase.RUNNING || phase == ExecutionPhase.ACCEPTED || phase == null ->
            if (steps.isNotEmpty() && steps.all { it.kind == "thinking" }) "已思考" else "已完成 ${count} 个步骤"
        else -> "${phase.label()} · ${count} 个步骤"
    }
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
@Composable internal fun CodeContent(title: String, text: String, read: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = raisedColor(), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.take(30), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionIcon("复制原始代码", { clipboard.setText(AnnotatedString(text)) }, AppIcons.Copy)
                ActionIcon(if (wrap) "关闭代码换行" else "代码自动换行", { wrap = !wrap }, AppIcons.Wrap)
                ActionIcon("放大代码", { read(title, text) }, AppIcons.Expand)
            }
            SelectionContainer {
                Text(text, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = wrap)
            }
        }
    }
}
@Composable internal fun ExecutionCard(turn: Turn, vm: ConversationViewModel, steps: List<Step> = turn.steps, showExtras: Boolean = true, read: (String, String) -> Unit) {
    if (steps.isEmpty()) return
    val expanded = turn.toolGroupExpanded(steps)
    val running = turn.occupied && turn.phase in setOf(ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL, ExecutionPhase.CANCELLING)
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = raisedColor(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { vm.enqueue { vm.actions.stepExpansion(turn.id, toolGroupKey(steps), !expanded) } }
                    .heightIn(min = 44.dp).padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(turn.executionHeadline(steps), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronRight, if (expanded) "已展开" else "已收起", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
            val scroll = rememberScrollState()
            LaunchedEffect(steps.size, steps.lastOrNull()?.id, steps.lastOrNull()?.summary, scroll.maxValue) {
                if (running) scroll.scrollTo(scroll.maxValue)
            }
            Column(Modifier.then(if (running) Modifier.heightIn(max = 168.dp).verticalScroll(scroll) else Modifier).padding(start = 2.dp, end = 8.dp, bottom = 8.dp)) {
                if (showExtras) turn.progress?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                steps.forEach { step ->
                    val thinking = step.kind == "thinking"
                    val open = !thinking && step.id in turn.expandedSteps
                    val view = remember(step.kind, step.summary, step.output) { ToolPresentation.step(step.kind, step.summary, step.output) }
                    Row(Modifier.fillMaxWidth().then(if (thinking) Modifier else Modifier.clickable { vm.enqueue {
                        vm.actions.stepExpansion(turn.id, step.id, !open)
                        if (!open) vm.actions.expansion(turn.id, true)
                    } }).heightIn(min = 40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepGlyph(step.kind, step.outcome, turn.phase, running && step.outcome == null)
                        Text(
                            view.title,
                            Modifier.weight(1f).padding(horizontal = 10.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!thinking) AppIcon(if (open) AppIcons.ChevronDown else AppIcons.ChevronRight, if (open) "已展开" else "已收起", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (open && !thinking) {
                        val body = view.detail.ifBlank { "尚无输出" }
                        Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 8.dp, bottom = 8.dp)) {
                            when {
                                view.terminal -> CodeContent(view.title, body, read)
                                ToolPresentation.looksLikeMarkdown(body) -> ReplyContent(body, streaming = running && step.outcome == null, read = read)
                                else -> SelectionContainer {
                                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                                }
                            }
                        }
                    }
                }
                if (showExtras && turn.diagnosticsActionVisible()) TextButton(onClick = { read("运行诊断", turn.diagnostics.joinToString("\n\n") { it.text }) }) { Text("查看诊断（${turn.diagnostics.size}）") }
            }
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
    val view = remember(permission.action, permission.scope) { ToolPresentation.permission(permission.action, permission.scope) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = raisedColor()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要你的授权", style = MaterialTheme.typography.titleMedium)
            Text(view.title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(view.detail, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            }
            if (submitted) Text("决定已接纳，等待执行结果")
            else if (busy) Text("正在提交决定…")
            else if (!connected) Text("连接中断，恢复连接后再确认")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { decide(false) }, enabled = enabled) { Text("拒绝") }
                Button(onClick = { decide(true) }, enabled = enabled) { Text("仅允许这一次") }
            }
        }
    }
}

internal fun stepStatusLabel(outcome: String?, phase: ExecutionPhase?): String = when (outcome) {
    "SUCCEEDED" -> "步骤完成"
    "FAILED" -> "步骤失败"
    "CANCELLED" -> "步骤已取消"
    null -> when (phase) {
        ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL -> "步骤进行中"
        ExecutionPhase.CANCELLING -> "步骤停止中"
        else -> "步骤结果未确认"
    }
    else -> "步骤结果未确认"
}

@Composable internal fun StepGlyph(kind: String, outcome: String?, phase: ExecutionPhase?, active: Boolean = false) {
    val icon = when (outcome) {
        "FAILED" -> AppIcons.Error
        "CANCELLED" -> AppIcons.Close
        else -> stepKindIcon(kind)
    }
    val reduced = rememberReducedMotion()
    val alpha = if (!active || reduced) 1f else {
        val pulse = rememberInfiniteTransition(label = "step")
        val value by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "step-alpha")
        value
    }
    AppIcon(icon, stepStatusLabel(outcome, phase), Modifier.size(18.dp).alpha(alpha), tint = when (outcome) {
        "FAILED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    })
}

@Composable internal fun StepStatus(outcome: String?, phase: ExecutionPhase?, active: Boolean = false) {
    StepGlyph("", outcome, phase, active)
}
