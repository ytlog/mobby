package com.github.ytlog.mobby.android.device

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

fun interface DevicePerformer {
    fun perform(plugin: String, action: String, args: Map<String, String>): String
}

object DeviceCommands {
    const val MAX_RESULT = 16_384
    fun token() = UUID.randomUUID().toString()
    fun handle(line: String, token: String, allow: Map<String, Set<String>>, performer: DevicePerformer): String {
        if (line.length > 65_536) return error("命令过长")
        val request = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            ?: return error("无法解析命令")
        if (request.text("token") != token) return error("未授权")
        val plugin = request.text("plugin").trim()
        val action = request.text("action").trim().lowercase()
        val allowed = allow[plugin]
        if (plugin.isEmpty() || action.isEmpty() || allowed == null || action !in allowed) return error("未授权")
        val fields = when (val argsValue = request["args"]) {
            null -> emptyMap()
            is JsonObject -> argsValue
            else -> return error("参数无效")
        }
        val args = linkedMapOf<String, String>()
        var count = 0
        for ((key, value) in fields) {
            if (++count > 16) return error("参数过多")
            if (!key.matches(Regex("[a-zA-Z][a-zA-Z0-9]{0,31}"))) return error("参数无效")
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return error("参数无效")
            if (text.length > 4_000) return error("参数过长")
            args[key] = text
        }
        return runCatching { ok(performer.perform(plugin, action, args)) }.getOrElse { error(it.message ?: "操作失败") }
    }
    private fun JsonObject.text(name: String): String {
        val value = this[name] as? JsonPrimitive ?: return ""
        return value.contentOrNull ?: ""
    }
    private fun ok(result: String) = buildJsonObject {
        put("ok", true)
        put("result", result.take(MAX_RESULT))
    }.toString()
    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message.take(240))
    }.toString()
}
