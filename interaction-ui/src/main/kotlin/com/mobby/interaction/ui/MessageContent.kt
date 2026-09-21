package com.mobby.interaction.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mobby.interaction.domain.*

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
@Composable internal fun ActionIcon(label: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, label) }
}
@Composable internal fun CodeContent(title: String, text: String, read: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                Text(title.take(30), Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.labelMedium)
                ActionIcon("复制原始代码", { clipboard.setText(AnnotatedString(text)) }, Icons.Outlined.ContentCopy)
                ActionIcon(if (wrap) "关闭代码换行" else "代码自动换行", { wrap = !wrap }, Icons.Outlined.WrapText)
                ActionIcon("放大代码", { read(title, text) }, Icons.Outlined.OpenInFull)
            }
            SelectionContainer {
                Text(text, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(12.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = wrap)
            }
        }
    }
}
@Composable internal fun ExecutionCard(turn: Turn, vm: ConversationViewModel, read: (String, String) -> Unit) {
    val expanded = ExecutionExpansion(turn.expanded).expanded(turn.phase ?: ExecutionPhase.ACCEPTED)
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { vm.enqueue { vm.actions.expansion(turn.id, !expanded) } }.padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${turn.phase.label()} · ${turn.steps.size} 个步骤", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            ActionIcon(if (expanded) "收起执行过程" else "展开执行过程", { vm.enqueue { vm.actions.expansion(turn.id, !expanded) } }, if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore)
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
            turn.progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (turn.steps.isEmpty() && turn.diagnostics.isEmpty()) Text(if (turn.occupied) "等待 Agent 返回公开进度…" else "没有工具执行步骤", style = MaterialTheme.typography.bodySmall)
            turn.steps.forEach { step ->
                val open = step.id in turn.expandedSteps
                Row(Modifier.fillMaxWidth().clickable { vm.enqueue {
                    vm.actions.stepExpansion(turn.id, step.id, !open)
                    if (!open) vm.actions.expansion(turn.id, true)
                } }.heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    StepStatus(step.outcome, turn.phase)
                    Text(step.summary.ifBlank { step.kind }, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "已展开" else "已收起")
                }
                if (open) SelectionContainer {
                    Text(step.output.ifBlank { "尚无输出" }, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (turn.diagnostics.isNotEmpty()) TextButton(onClick = { read("运行诊断", turn.diagnostics.joinToString("\n\n") { it.text }) }) { Text("查看诊断（${turn.diagnostics.size}）") }
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要你的授权", style = MaterialTheme.typography.titleMedium)
            Text(permission.action, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(permission.scope, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
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

@Composable internal fun StepStatus(outcome: String?, phase: ExecutionPhase?) {
    val (icon, label) = when (outcome) {
        "SUCCEEDED" -> Icons.Outlined.Check to "步骤完成"
        "FAILED" -> Icons.Outlined.ErrorOutline to "步骤失败"
        "CANCELLED" -> Icons.Outlined.Cancel to "步骤已取消"
        null -> when (phase) {
            ExecutionPhase.ACCEPTED, ExecutionPhase.RUNNING, ExecutionPhase.AWAITING_APPROVAL -> Icons.Outlined.MoreHoriz to "步骤进行中"
            ExecutionPhase.CANCELLING -> Icons.Outlined.MoreHoriz to "步骤停止中"
            else -> Icons.Outlined.HelpOutline to "步骤结果未确认"
        }
        else -> Icons.Outlined.HelpOutline to "步骤结果未确认"
    }
    Icon(icon, label, Modifier.size(20.dp))
}
