package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.conversation.domain.PermissionSubject
import com.github.ytlog.mobby.android.conversation.domain.Step

/**
 * Timeline labels for a typed conversation step or approval.
 */
internal data class ToolView(val title: String, val detail: String, val terminal: Boolean)

internal object ToolPresentation {
    fun present(step: Step): ToolView = when (step) {
        is Step.Thinking -> ToolView(AppStrings.thinking, step.text.trim(), false)
        is Step.Command -> {
            val command = step.command.trim()
            val body = buildString {
                if (command.isNotBlank()) append(AppStrings.commandN).append(command)
                if (isNotEmpty()) append("\n\n")
                append(AppStrings.resultN).append(step.result.ifBlank { AppStrings.noOutput2 })
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
        is PermissionSubject.Command -> ToolView(title("command_execution", subject.command.lineSequence().firstOrNull().orEmpty()), AppStrings.commandN2(subject.command).trim(), true)
        is PermissionSubject.FileRead -> {
            val range = listOf(
                subject.offset.takeIf { it.isNotBlank() }?.let { AppStrings.startLineN(it) },
                subject.limit.takeIf { it.isNotBlank() }?.let { AppStrings.lineCountN(it) },
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
        val label = KIND_LABELS[kind.lowercase()] ?: kind.ifBlank { AppStrings.step }
        val focus = headline.trim()
        return if (focus.isBlank() || focus.equals(kind, true) || focus.equals(label, true)) label
        else if (focus.startsWith(label)) focus
        else "$label $focus"
    }

    private val READER_SECTION_LABELS get() = setOf(AppStrings.command, AppStrings.result, AppStrings.startLine, AppStrings.lineCount)

    private val KIND_LABELS get() = mapOf(
        "read" to AppStrings.read,
        "write" to AppStrings.write,
        "edit" to AppStrings.edit2,
        "editnotebook" to AppStrings.editNotebook,
        "bash" to AppStrings.run2,
        "shell" to AppStrings.run2,
        "command_execution" to AppStrings.run2,
        "grep" to AppStrings.searchCode,
        "glob" to AppStrings.findFiles,
        "ls" to AppStrings.list,
        "websearch" to AppStrings.searchWeb,
        "web_search" to AppStrings.searchWeb,
        "webfetch" to AppStrings.openWebPage,
        "file_change" to AppStrings.modifyFiles,
        "mcp_tool_call" to AppStrings.callTool,
        "snapshot" to AppStrings.readScreen,
        "mcp__phone__snapshot" to AppStrings.readScreen,
        "click" to AppStrings.click,
        "mcp__phone__click" to AppStrings.click,
        "type" to AppStrings.input,
        "mcp__phone__type" to AppStrings.input,
        "tap" to AppStrings.tap,
        "mcp__phone__tap" to AppStrings.tap,
        "back" to AppStrings.back,
        "mcp__phone__back" to AppStrings.back,
        "home" to AppStrings.homeScreen,
        "mcp__phone__home" to AppStrings.homeScreen,
        "recents" to AppStrings.recentApps,
        "mcp__phone__recents" to AppStrings.recentApps,
        "thinking" to AppStrings.thinking,
        "todo_list" to AppStrings.toDo,
        "task" to AppStrings.task,
        "tool" to AppStrings.tool,
    )
}
