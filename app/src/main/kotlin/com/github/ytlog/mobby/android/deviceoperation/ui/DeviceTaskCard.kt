package com.github.ytlog.mobby.android.deviceoperation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*

class DeviceCardActions(
    val stop: (() -> Unit)? = null,
    val openConversation: (() -> Unit)? = null,
    val openResource: ((String) -> Unit)? = null,
    val respond: ((String) -> Unit)? = null,
    val inspectEffect: (() -> Unit)? = null,
)

/** Fixed components only. Rendering/recomposition has no execution, retry or navigation effects. */
@Composable fun DeviceTaskCard(operation: DeviceOperation, actions: DeviceCardActions, modifier: Modifier = Modifier,
    stopping: Boolean = false, thumbnail: (@Composable (String) -> Unit)? = null) {
    var expanded by rememberSaveable(operation.operationId, operation.status.terminal) { mutableStateOf(false) }
    val status = if (stopping && !operation.status.terminal) DeviceLabels.status(DeviceStatus.CANCELLING) else DeviceLabels.phase(operation)
    val title = DeviceLabels.title(operation.plugin, operation.action)
    val detailsVisible = !operation.status.terminal || expanded
    val result = operation.result
    val collapsedPhoto = operation.status.terminal && !expanded && operation.displayType == "capture" &&
        result?.kind == "capture_resource" && result.data.text("mediaType").startsWith("image/")
    Surface(modifier.fillMaxWidth().semantics { contentDescription = title; stateDescription = status },
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().then(if (operation.status.terminal) Modifier.clickable { expanded = !expanded } else Modifier).heightIn(min = 44.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.labelMedium,
                    color = if (operation.status == DeviceStatus.FAILED || operation.status == DeviceStatus.UNCONFIRMED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (operation.status.terminal) {
                Spacer(Modifier.width(8.dp))
                val chevron = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(16.dp)) {
                    val first = if (detailsVisible) Offset(size.width * .2f, size.height * .38f) else Offset(size.width * .38f, size.height * .2f)
                    val middle = if (detailsVisible) Offset(size.width * .5f, size.height * .68f) else Offset(size.width * .68f, size.height * .5f)
                    val last = if (detailsVisible) Offset(size.width * .8f, size.height * .38f) else Offset(size.width * .38f, size.height * .8f)
                    drawLine(chevron, first, middle, 2.dp.toPx())
                    drawLine(chevron, middle, last, 2.dp.toPx())
                }
            }
        }
        if (collapsedPhoto && thumbnail != null) {
            result?.resourceRefs?.firstOrNull()?.let { thumbnail(it) }
        }
        if (detailsVisible) {
            if (operation.subject.label.isNotBlank()) Text(operation.subject.label, style = MaterialTheme.typography.bodyMedium)
            if (operation.subject.detail.isNotBlank()) Caption(operation.subject.detail)
            when (operation.displayType) {
                "message_send" -> MessageSendCard(operation)
                "message_list", "file_list" -> FileListCard(operation, expanded, actions)
                "media_grid" -> MediaGridCard(operation, expanded, actions, thumbnail)
                "batch_change", "file_transfer", "record_change" -> ChangeCard(operation, expanded, actions)
                "capture" -> CaptureCard(operation, actions, thumbnail)
                "measurement", "connection" -> MeasurementCard(operation, expanded)
                "system_handoff" -> SystemHandoffCard(operation)
                "screen_control" -> ScreenControlCard(operation, expanded, actions, thumbnail)
                else -> BasicDeviceCard(operation, expanded)
            }
            operation.progress?.let { progress ->
                val total = progress.total
                if (total != null && total > 0) LinearProgressIndicator(progress = { progress.completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Caption(if (total == null) DeviceLabels.text("已处理 ${progress.completed} ${progress.unit}", "Processed ${progress.completed} ${progress.unit}")
                    else "${progress.completed} / $total ${progress.unit}")
            }
            operation.error?.let { Text(DeviceLabels.error(it.code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (operation.result?.effectState in setOf(EffectState.SUBMITTED, EffectState.UNKNOWN) || operation.error?.effectState == EffectState.UNKNOWN)
                Caption(DeviceLabels.text("可能已发生外部操作，请先核对结果，不要重复提交。", "An external effect may have occurred. Check the result before submitting again."))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (DeviceButton.STOP_RUN in operation.availableActions && !operation.status.terminal && actions.stop != null)
                    TextButton(onClick = actions.stop, enabled = !stopping && operation.status != DeviceStatus.CANCELLING) { Text(DeviceLabels.text("停止任务", "Stop task")) }
                if (DeviceButton.RESPOND in operation.availableActions && actions.respond != null && !stopping && !operation.status.terminal)
                    operation.requiresAttention?.allowedResponses?.filter { it in setOf("cancel", "continue", "confirm") }?.forEach { response ->
                        TextButton(onClick = { actions.respond.invoke(response) }) { Text(when (response) {
                            "cancel" -> if (operation.plugin == "appfunction") DeviceLabels.text("取消调用", "Cancel call") else DeviceLabels.text("取消采集", "Cancel capture")
                            "confirm" -> if (operation.plugin == "appfunction") DeviceLabels.text("确认调用", "Confirm call") else DeviceLabels.text("确认使用", "Use capture")
                            else -> DeviceLabels.text("继续", "Continue")
                        }) }
                    }
                if (DeviceButton.OPEN_CONVERSATION in operation.availableActions && actions.openConversation != null)
                    TextButton(onClick = actions.openConversation) { Text(DeviceLabels.text("回到对话", "Open conversation")) }
                if (DeviceButton.INSPECT_EFFECT in operation.availableActions && actions.inspectEffect != null)
                    TextButton(onClick = actions.inspectEffect) { Text(DeviceLabels.text("核对结果", "Check result")) }
            }
            if (expanded) {
                HorizontalDivider()
                Caption("${operation.plugin}.${operation.action}")
                Caption(DeviceLabels.text("动作编号：", "Operation: ") + operation.operationId)
                if (operation.displayType !in knownDisplays) Text(operation.result?.data?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    }
}

private val knownDisplays = setOf("basic", "screen_control", "message_list", "message_send", "media_grid", "batch_change", "file_list", "file_transfer", "capture", "record_change", "measurement", "connection", "system_handoff")
private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.objects(key: String) = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
@Composable private fun Caption(text: String) { if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Input(operation: DeviceOperation) {
    operation.input.forEach { (name, value) -> if (value is JsonPrimitive && value.contentOrNull?.isNotBlank() == true)
        Caption("${DeviceLabels.field(name)}：${value.content}") }
}
@Composable private fun ResourceButtons(operation: DeviceOperation, actions: DeviceCardActions) {
    if (DeviceButton.OPEN_RESOURCE !in operation.availableActions || actions.openResource == null) return
    operation.result?.resourceRefs?.forEachIndexed { index, ref ->
        TextButton(onClick = { actions.openResource.invoke(ref) }) { Text(DeviceLabels.text("预览附件 ${index + 1}", "Preview attachment ${index + 1}")) }
    }
}
@Composable fun MessageSendCard(operation: DeviceOperation) {
    val input = operation.input
    if (input.text("recipientLabel").isNotBlank()) Caption(input.text("recipientLabel"))
    if (input.text("recipientMasked").isNotBlank()) Caption(DeviceLabels.text("号码尾号 ", "Number ending ") + input.text("recipientMasked"))
    if (input.text("body").isNotBlank()) Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(input.text("body"), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
    val result = operation.result?.takeIf { it.kind == "message_receipt" }?.data ?: return
    Caption(when (result.text("sent")) {
        "succeeded" -> DeviceLabels.text("发送回执：已发出", "Sent receipt: sent")
        "failed" -> DeviceLabels.text("发送回执：发送失败", "Sent receipt: failed")
        else -> DeviceLabels.text("发送回执：尚未确认", "Sent receipt: unconfirmed")
    })
    Caption(when (result.text("delivered")) {
        "succeeded" -> DeviceLabels.text("送达回执：已送达", "Delivery receipt: delivered")
        "failed" -> DeviceLabels.text("送达回执：未能送达", "Delivery receipt: failed")
        else -> DeviceLabels.text("尚未收到送达确认", "No delivery confirmation received")
    })
}
@Composable fun FileListCard(operation: DeviceOperation, expanded: Boolean, actions: DeviceCardActions) {
    Input(operation)
    val data = operation.result?.data ?: return
    Caption(DeviceLabels.scope(data.text("scope")))
    val items = data.objects("items")
    Caption(DeviceLabels.text("返回 ${items.size} 项", "${items.size} items returned"))
    items.take(if (expanded) 100 else 3).forEach { item ->
        HorizontalDivider()
        item.forEach { (key, value) -> if (key !in setOf("itemId", "resourceRef") && value is JsonPrimitive) Caption("${DeviceLabels.field(key)}：${value.contentOrNull.orEmpty()}") }
    }
    if (!expanded && items.size > 3) Caption(DeviceLabels.text("展开记录查看其余条目", "Expand details for remaining items"))
    ResourceButtons(operation, actions)
}
@Composable fun MediaGridCard(operation: DeviceOperation, expanded: Boolean, actions: DeviceCardActions, thumbnail: (@Composable (String) -> Unit)?) {
    val data = operation.result?.data
    if (data == null) { Input(operation); return }
    Caption(DeviceLabels.scope(data.text("scope")))
    val items = data.objects("items").take(if (expanded) 100 else 6)
    items.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        row.forEach { item -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ref = item.text("resourceRef")
            if (thumbnail != null && ref in operation.result!!.resourceRefs) thumbnail(ref)
            Caption(item.text("name"))
            Caption(item.text("kind"))
        } }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    } }
    ResourceButtons(operation, actions)
}
@Composable fun ChangeCard(operation: DeviceOperation, expanded: Boolean, actions: DeviceCardActions) {
    Input(operation)
    val result = operation.result ?: return
    when (result.kind) {
        "batch_result" -> {
            Caption(DeviceLabels.text("成功 ${result.data.text("succeeded")} 项，失败 ${result.data.text("failed")} 项", "Succeeded ${result.data.text("succeeded")}; failed ${result.data.text("failed")}"))
            if (result.data["unprocessed"] != JsonNull) Caption(DeviceLabels.text("未处理：", "Unprocessed: ") + result.data.text("unprocessed"))
        }
        "file_items" -> FileListCard(operation.copy(input = JsonObject(emptyMap())), expanded, DeviceCardActions())
        else -> {
            val after = result.data["after"] as? JsonObject
            after?.forEach { (key, value) -> Caption("${DeviceLabels.field(key)}：${(value as? JsonPrimitive)?.contentOrNull.orEmpty()}") }
            if (expanded) result.data.text("previewText").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    ResourceButtons(operation, actions)
}
@Composable fun CaptureCard(operation: DeviceOperation, actions: DeviceCardActions, thumbnail: (@Composable (String) -> Unit)?) {
    val result = operation.result
    if (result == null) Caption(DeviceLabels.text("在采集面板完成操作，确认使用后素材才会交给任务。", "Complete the capture panel and confirm before supplying material to the task."))
    else {
        if (result.kind == "capture_resource" && result.data.text("mediaType").startsWith("image/") && thumbnail != null)
            result.resourceRefs.firstOrNull()?.let { thumbnail(it) }
        Caption(DeviceLabels.text("素材已提供；不代表后续识别或分析已完成。", "Capture supplied; subsequent recognition or analysis is a separate step."))
        ResourceButtons(operation, actions)
    }
}
@Composable fun MeasurementCard(operation: DeviceOperation, expanded: Boolean) {
    Input(operation)
    val result = operation.result ?: return
    Caption(result.data.text("source"))
    if (result.kind == "measurement") result.data.objects("samples").take(if (expanded) 100 else 4).forEach { sample ->
        Text("${sample.text("name")}：${sample.text("value")} ${sample.text("unit")}", style = MaterialTheme.typography.bodyMedium)
        Caption(DeviceLabels.text("采样时间：", "Sample time: ") + sample.text("sampledAtEpochMillis"))
        if (sample["accuracy"] != null && sample["accuracy"] != JsonNull) Caption(DeviceLabels.text("精度：", "Accuracy: ") + sample.text("accuracy"))
    } else if (result.kind == "connection_state") Caption(result.data.text("state"))
}
@Composable fun SystemHandoffCard(operation: DeviceOperation) {
    Input(operation)
    Caption(DeviceLabels.text("请在系统或目标应用中完成操作。返回后需要重新核对结果。", "Complete the system or target app step. The result must be checked on return."))
    operation.result?.takeIf { it.kind == "handoff_result" }?.let {
        Caption(it.data.text("target"))
        if (it.data["opened"]?.jsonPrimitive?.booleanOrNull == true) Caption(DeviceLabels.text("已打开目标页面；不等于业务已完成。", "Target opened; this does not confirm business completion."))
    }
}
@Composable fun ScreenControlCard(operation: DeviceOperation, expanded: Boolean, actions: DeviceCardActions,
    thumbnail: (@Composable (String) -> Unit)?) {
    Input(operation)
    operation.result?.takeIf { it.kind == "screen_observation" }?.let { result ->
        Caption(result.data.text("packageName"))
        if (result.data["actionConfirmed"]?.jsonPrimitive?.booleanOrNull == true) Caption(DeviceLabels.text("设备动作已确认，业务结果需另行核对。", "Device action confirmed; business outcome needs separate verification."))
        val ref = result.data.text("observationRef")
        if (ref in result.resourceRefs) {
            if (thumbnail != null) thumbnail(ref)
            else Caption(DeviceLabels.text("屏幕截图已保存", "Screen screenshot saved"))
        } else Caption(DeviceLabels.text("未获取到屏幕截图：", "Screen screenshot unavailable: ") + screenScreenshotReason(result.data.text("screenshotStatus")))
        if (expanded) Text(result.data.text("text"), style = MaterialTheme.typography.bodySmall)
        ResourceButtons(operation, actions)
    }
}
private fun screenScreenshotReason(status: String): String = when (status) {
    "rate_limited" -> DeviceLabels.text("系统限制了截图频率", "Screenshot rate limited by Android")
    "protected_window" -> DeviceLabels.text("当前窗口禁止截图", "Current window blocks screenshots")
    "accessibility_unavailable", "permission_denied" -> DeviceLabels.text("无障碍截图权限不可用", "Accessibility screenshot access unavailable")
    "unsupported_android_version" -> DeviceLabels.text("Android 版本不支持", "Android version does not support it")
    "resource_storage_failed" -> DeviceLabels.text("截图无法保存", "Screenshot could not be saved")
    "timeout" -> DeviceLabels.text("系统截图超时", "Android screenshot timed out")
    "image_encoding_failed" -> DeviceLabels.text("截图处理失败", "Screenshot processing failed")
    else -> DeviceLabels.text("系统截图失败", "Android screenshot failed")
}
@Composable fun BasicDeviceCard(operation: DeviceOperation, expanded: Boolean) {
    Input(operation)
    if (operation.result?.kind == "text") {
        val text = operation.result!!.data.text("text")
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 4)
    } else if (operation.result?.kind == "app_function") {
        val value = operation.result!!.data["value"]?.toString().orEmpty()
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 6)
    } else if (operation.result != null) Caption(DeviceLabels.text("此结果类型尚不支持展示，可展开查看记录。", "This result type is not supported; expand its record."))
}

/** Candidate/excluded capability has no operation identity and cannot look like a completed action. */
@Composable fun DeviceUnavailableCard(title: String, reason: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(DeviceLabels.text("当前不可用", "Currently unavailable"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}
