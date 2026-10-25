package com.github.ytlog.mobby.android.deviceoperation.ui

import kotlinx.serialization.json.*

/** Summary lines for program development results (`validate`, `dry-run`, `records`); the full record stays in the operation. */
internal fun programReportLines(data: JsonObject): List<String> = when {
    "valid" in data -> if (data.flag("valid")) listOf(DeviceLabels.text(
        "校验通过：${data.string("id")} ${data.string("version")}，${data.string("files")} 个文件",
        "Valid: ${data.string("id")} ${data.string("version")}, ${data.string("files")} files")) +
        data.strings("warnings").map { DeviceLabels.text("提示：", "Note: ") + it }
    else listOf(DeviceLabels.text("校验未通过", "Not valid")) + data.strings("problems")
    "ran" in data -> if (!data.flag("ran")) listOf(DeviceLabels.text("试运行未开始", "Dry run did not start")) + data.strings("problems")
    else listOf(DeviceLabels.text("试运行结束：", "Dry run ended: ") + listOfNotNull(data.string("outcome"), data.string("reason")).joinToString(" · ") +
        if (data.flag("timedOut")) DeviceLabels.text("（超时）", " (timed out)") else "") +
        data.objects("calls").map { call ->
            listOfNotNull(call.string("capability"), call.string("status"),
                call.string("error")?.let { error -> listOfNotNull(error, call.string("reason")).joinToString(": ") }).joinToString(" · ")
        } +
        data.objects("logs").map { log -> DeviceLabels.text("日志：", "Log: ") + listOfNotNull(log.string("message"), log.string("data")).joinToString(" ") }
    else -> listOf(DeviceLabels.text(
        "记录：${data.objects("runs").size} 次运行，${data.objects("calls").size} 次调用，${data.objects("logs").size} 条日志",
        "Records: ${data.objects("runs").size} runs, ${data.objects("calls").size} calls, ${data.objects("logs").size} logs"))
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
