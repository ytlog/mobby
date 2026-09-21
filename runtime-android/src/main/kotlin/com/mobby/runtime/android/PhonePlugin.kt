package com.mobby.runtime.android

import com.mobby.runtime.api.CapabilityRef
import com.mobby.runtime.api.ErrorCode
import com.mobby.runtime.api.PluginSummary
import com.mobby.runtime.api.RuntimeError
import org.json.JSONObject
import java.util.UUID

internal object PhonePlugin {
    const val REF = "plugin:PHONE:ACCESSIBILITY"
    fun accepts(ref: CapabilityRef) = ref.value == REF
    fun summary(available: Boolean) = PluginSummary(
        CapabilityRef(REF), "使用当前手机",
        "通过系统无障碍服务读取当前屏幕并操作本机应用。仅在你开启系统授权、并把此插件加入本轮草稿后，Agent 才能使用。",
        available, if (available) null else RuntimeError(ErrorCode.PERMISSION_DENIED)
    )
    fun instruction(helper: String) = """
请使用本机无障碍桥接操作当前手机。仅调用下列命令，不要猜测其他接口：
node $helper snapshot
node $helper click <屏幕可见文字>
node $helper type <要输入的文字>
node $helper tap <0到1的x> <0到1的y>
node $helper back
node $helper home
node $helper recents
snapshot 返回当前可见界面的精简树；密码框显示为 [secure]。操作前先 snapshot。用户未开启系统无障碍或未选择此插件时不要假装已操作成功。
""".trimIndent()
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
