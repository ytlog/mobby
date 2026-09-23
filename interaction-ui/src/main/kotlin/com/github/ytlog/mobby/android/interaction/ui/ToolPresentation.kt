package com.github.ytlog.mobby.android.interaction.ui

import org.json.JSONArray
import org.json.JSONObject
import com.github.ytlog.mobby.android.interaction.domain.Step

/**
 * Timeline labels for a conversation step. The step's fields are already typed.
 * Approval scope is a separate payload and is formatted here.
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

    fun permission(action: String, scope: String): ToolView {
        val presented = present(scope)
        val title = title(action, presented.headline)
        val detail = if (presented.fields.isEmpty()) presented.body.ifBlank { scope }
        else presented.fields.entries.joinToString("\n\n") { (key, value) -> "$key\n$value" }
        return ToolView(title.ifBlank { action }, detail, presented.terminal)
    }

    fun looksLikeMarkdown(text: String): Boolean {
        val sample = text.trim()
        return sample.contains("```") || sample.startsWith("#") || sample.contains("\n# ") ||
            sample.startsWith("- ") || sample.contains("\n- ") || sample.contains("**")
    }

    private data class Presented(val headline: String, val fields: Map<String, String>, val body: String, val terminal: Boolean)

    private fun present(raw: String): Presented {
        val text = raw.trim()
        if (text.isEmpty()) return Presented("", emptyMap(), "", false)
        val parsed = runCatching {
            when {
                text.startsWith("{") -> fields(JSONObject(text))
                text.startsWith("[") -> array(JSONArray(text))
                else -> null
            }
        }.getOrNull()
        return parsed ?: Presented("", emptyMap(), text, false)
    }

    private fun fields(obj: JSONObject): Presented {
        val values = linkedMapOf<String, String>()
        for (key in PRIORITY_KEYS) {
            val rendered = render(obj.opt(key)) ?: continue
            values[label(key)] = rendered
        }
        obj.keys().asSequence().toList().filterNot { it in PRIORITY_KEYS || it in HIDDEN_KEYS }.sorted().forEach { key ->
            render(obj.opt(key))?.let { values[label(key)] = it }
        }
        val headline = PRIORITY_KEYS.firstNotNullOfOrNull { key ->
            obj.opt(key)?.let { render(it)?.takeIf { value -> value.lines().size == 1 && value.length <= 120 } }
        }.orEmpty()
        val body = values.values.singleOrNull() ?: values.entries.joinToString("\n\n") { (key, value) -> "$key\n$value" }
        return Presented(headline, values, body, false)
    }

    private fun array(array: JSONArray): Presented {
        val texts = (0 until array.length()).mapNotNull { index ->
            when (val item = array.opt(index)) {
                is JSONObject -> item.optString("text").ifBlank { render(item) }
                JSONObject.NULL, null -> null
                else -> item.toString()
            }?.takeIf { it.isNotBlank() }
        }
        val body = texts.joinToString("\n\n")
        return Presented(texts.firstOrNull()?.takeIf { it.lines().size == 1 && it.length <= 120 }.orEmpty(), emptyMap(), body, false)
    }

    private fun render(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> present(value.toString()).body.takeIf { it.isNotBlank() }
        is JSONArray -> array(value).body.takeIf { it.isNotBlank() }
        is Number, is Boolean -> value.toString()
        else -> value.toString().ifBlank { null }
    }

    private fun title(kind: String, headline: String): String {
        val label = KIND_LABELS[kind.lowercase()] ?: kind.ifBlank { "步骤" }
        val focus = headline.trim()
        return if (focus.isBlank() || focus.equals(kind, true) || focus.equals(label, true)) label
        else if (focus.startsWith(label)) focus
        else "$label $focus"
    }

    private fun label(key: String) = FIELD_LABELS[key] ?: key

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
    private val FIELD_LABELS = mapOf(
        "command" to "命令",
        "cmd" to "命令",
        "file_path" to "文件",
        "path" to "路径",
        "file" to "文件",
        "query" to "查询",
        "pattern" to "匹配",
        "glob" to "范围",
        "url" to "链接",
        "description" to "说明",
        "old_string" to "原文",
        "new_string" to "替换为",
        "content" to "内容",
        "offset" to "起始行",
        "limit" to "行数",
    )
    private val PRIORITY_KEYS = listOf(
        "command", "cmd", "query", "pattern", "glob", "file_path", "path", "file", "url",
        "description", "old_string", "new_string", "content", "offset", "limit",
    )
    private val HIDDEN_KEYS = setOf("type", "id", "tool_use_id", "is_error", "name")
}
