package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.CapabilityRef
import com.github.ytlog.mobby.android.runtime.api.ErrorCode
import com.github.ytlog.mobby.android.runtime.api.PluginSummary
import com.github.ytlog.mobby.android.runtime.api.RuntimeError
import com.github.ytlog.mobby.android.runtime.engine.SkillDocument
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object PhonePlugin {
    const val REF = "plugin:PHONE:ACCESSIBILITY"
    const val SKILL = "use-current-phone"
    fun accepts(ref: CapabilityRef) = ref.value == REF
    fun summary(available: Boolean) = PluginSummary(
        CapabilityRef(REF), "使用当前手机",
        "通过无障碍读取并操作当前屏幕",
        available, if (available) null else RuntimeError(ErrorCode.PERMISSION_DENIED)
    )
    fun write(root: File, node: String, port: Int, token: String): File {
        require(node.startsWith("/") && '\u0000' !in node)
        val skillDir = File(root, "skills/$SKILL")
        val scripts = File(skillDir, "scripts")
        scripts.mkdirs()
        val helper = PhoneCommandServer.helper(scripts, port, token)
        val skill = File(skillDir, "SKILL.md")
        val markdown = """
            ---
            name: $SKILL
            description: Operate the current Android phone screen with snapshot, click, type, tap, back, home, and recents.
            ---

            Use this skill whenever the user wants to read or control the current phone.

            Always snapshot first. Password fields appear as `[secure]`. Do not invent missing controls. If a command fails, report the failure; do not pretend it succeeded.

            Run the bundled helper with this Node binary. It only talks to this device:

            `$node ${helper.absolutePath} snapshot`
            `$node ${helper.absolutePath} click <visible-text>`
            `$node ${helper.absolutePath} type <text>`
            `$node ${helper.absolutePath} tap <x> <y>`
            `$node ${helper.absolutePath} back`
            `$node ${helper.absolutePath} home`
            `$node ${helper.absolutePath} recents`

            `x` and `y` are 0 to 1. Keep queries under 200 characters and typed text under 2000 characters.
        """.trimIndent() + "\n"
        require(SkillDocument.preview(markdown).issues.isEmpty())
        skill.writeText(markdown)
        File(root, "plugin.json").writeText(
            """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"$SKILL","version":"1.0.0","description":"Operate the current Android phone screen"}"""
        )
        File(root, ".codex-plugin").mkdirs()
        File(root, ".codex-plugin/plugin.json").writeText(
            """{"name":"$SKILL","version":"1.0.0","description":"Operate the current Android phone screen","skills":"./skills/"}"""
        )
        File(root, ".claude-plugin").mkdirs()
        File(root, ".claude-plugin/plugin.json").writeText(
            """{"name":"$SKILL","version":"1.0.0","description":"Operate the current Android phone screen"}"""
        )
        return skill
    }
}

internal fun interface PhoneOperator {
    fun perform(action: String, args: Map<String, String>): String
}

internal object PhoneCommands {
    fun handle(line: String, token: String, operator: PhoneOperator): String {
        val request = runCatching { JSONObject(line) }.getOrElse { return error("无法解析命令") }
        if (request.optString("token") != token) return error("未授权")
        val action = request.optString("action").trim().lowercase()
        if (action.isEmpty()) return error("缺少 action")
        val args = buildMap {
            listOf("query", "text", "x", "y").forEach { key ->
                if (request.has(key)) put(key, request.opt(key)?.toString() ?: return@forEach)
            }
        }
        if ((args["query"]?.length ?: 0) > 200 || (args["text"]?.length ?: 0) > 2000) return error("参数过长")
        return runCatching { ok(operator.perform(action, args)) }.getOrElse { error(it.message ?: "操作失败") }
    }
    fun token() = UUID.randomUUID().toString()
    private fun ok(result: String) = JSONObject().put("ok", true).put("result", result.take(16_384)).toString()
    private fun error(message: String) = JSONObject().put("ok", false).put("error", message.take(240)).toString()
}
