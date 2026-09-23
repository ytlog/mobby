package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.PermissionSubject
import com.github.ytlog.mobby.android.interaction.domain.Step

/**
 * Timeline labels for a typed conversation step or approval.
 */
internal data class ToolView(val title: String, val detail: String, val terminal: Boolean)

internal object ToolPresentation {
    fun present(step: Step): ToolView = when (step) {
        is Step.Thinking -> ToolView("思考", step.text.trim(), false)
        is Step.Command -> {
            val command = step.command.trim()
            val body = buildString {
                if (command.isNotBlank()) append("命令\n").append(command)
                if (isNotEmpty()) append("\n\n")
                append("结果\n").append(step.result.ifBlank { "无输出" })
            }
            ToolView(title("command_execution", command.lineSequence().firstOrNull().orEmpty()), body, true)
        }
        is Step.FileRead -> ToolView(title("read", step.path), step.content.trim(), step.content.isNotBlank())
        is Step.FileWrite -> ToolView(title("write", step.path), step.content.trim(), step.content.isNotBlank())
        is Step.FileDiff -> {
            val path = step.paths.firstOrNull().orEmpty().substringAfterLast('/').ifBlank { step.paths.firstOrNull().orEmpty() }
            ToolView(title("file_change", path), step.diff.trim(), step.diff.isNotBlank())
        }
        is Step.Action -> {
            val focus = step.detail.lineSequence().firstOrNull()?.trim().orEmpty()
            val extra = step.detail.trim()
            val body = buildString {
                if (extra.isNotBlank() && extra != focus) append(extra)
                if (step.result.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(step.result.trim())
                }
            }
            ToolView(title(step.name, focus), body.trim(), false)
        }
    }

    fun permission(subject: PermissionSubject): ToolView = when (subject) {
        is PermissionSubject.Command -> ToolView(title("command_execution", subject.command.lineSequence().firstOrNull().orEmpty()), "命令\n${subject.command}".trim(), true)
        is PermissionSubject.FileRead -> {
            val range = listOf(
                subject.offset.takeIf { it.isNotBlank() }?.let { "起始行\n$it" },
                subject.limit.takeIf { it.isNotBlank() }?.let { "行数\n$it" },
            ).filterNotNull().joinToString("\n\n")
            ToolView(title("read", subject.path), range, false)
        }
        is PermissionSubject.FileWrite -> ToolView(title("write", subject.path), subject.content, subject.content.isNotBlank())
        is PermissionSubject.FileDiff -> {
            val path = subject.paths.firstOrNull().orEmpty().substringAfterLast('/').ifBlank { subject.paths.firstOrNull().orEmpty() }
            ToolView(title("file_change", path), subject.diff, subject.diff.isNotBlank())
        }
        is PermissionSubject.Action -> ToolView(title(subject.name, subject.detail.lineSequence().firstOrNull().orEmpty()), subject.detail, false)
    }

    fun readerSectionLabel(lines: List<String>, index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        if (line !in READER_SECTION_LABELS) return false
        val afterBreak = index == 0 || lines[index - 1].isBlank()
        val hasValue = index + 1 < lines.size && lines[index + 1].isNotBlank()
        return afterBreak && hasValue
    }

    fun looksLikeMarkdown(text: String): Boolean {
        val sample = text.trim()
        return sample.contains("```") || sample.startsWith("#") || sample.contains("\n# ") ||
            sample.startsWith("- ") || sample.contains("\n- ") || sample.contains("**")
    }

    private fun title(kind: String, headline: String): String {
        val label = KIND_LABELS[kind.lowercase()] ?: kind.ifBlank { "步骤" }
        val focus = headline.trim()
        return if (focus.isBlank() || focus.equals(kind, true) || focus.equals(label, true)) label
        else if (focus.startsWith(label)) focus
        else "$label $focus"
    }

    private val READER_SECTION_LABELS = setOf("命令", "结果", "起始行", "行数")

    private val KIND_LABELS = mapOf(
        "read" to "读取",
        "write" to "写入",
        "edit" to "编辑",
        "editnotebook" to "编辑笔记",
        "bash" to "运行",
        "shell" to "运行",
        "command_execution" to "运行",
        "grep" to "搜索代码",
        "glob" to "查找文件",
        "ls" to "列出",
        "websearch" to "搜索网页",
        "web_search" to "搜索网页",
        "webfetch" to "打开网页",
        "file_change" to "修改文件",
        "mcp_tool_call" to "调用工具",
        "snapshot" to "读取屏幕",
        "mcp__phone__snapshot" to "读取屏幕",
        "click" to "点击",
        "mcp__phone__click" to "点击",
        "type" to "输入",
        "mcp__phone__type" to "输入",
        "tap" to "点按",
        "mcp__phone__tap" to "点按",
        "back" to "返回",
        "mcp__phone__back" to "返回",
        "home" to "主屏幕",
        "mcp__phone__home" to "主屏幕",
        "recents" to "最近任务",
        "mcp__phone__recents" to "最近任务",
        "thinking" to "思考",
        "todo_list" to "待办",
        "task" to "任务",
        "tool" to "工具",
    )
}
