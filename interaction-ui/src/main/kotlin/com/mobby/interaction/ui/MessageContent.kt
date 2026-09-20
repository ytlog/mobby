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
@Composable internal fun ReplyContent(text: String, read: (String, String) -> Unit) {
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) { if (part.isNotBlank()) SelectionContainer { Text(part.trim(), style = MaterialTheme.typography.bodyLarge) } }
            else {
                val language = part.substringBefore('\n').trim()
                val code = if ('\n' in part) part.substringAfter('\n').removeSuffix("\n") else part
                CodeContent(language.ifBlank { "代码" }, code, read)
            }
        }
    }
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
                    Icon(if (step.outcome == "FAILED") Icons.Outlined.ErrorOutline else if (step.outcome != null) Icons.Outlined.Check else Icons.Outlined.MoreHoriz,
                        step.outcome ?: "步骤进行中", Modifier.size(20.dp))
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
