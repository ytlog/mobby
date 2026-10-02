package com.github.ytlog.mobby.android.deviceoperation.ui

import com.github.ytlog.mobby.android.runtime.api.device.*

import com.github.ytlog.mobby.android.localization.AppLanguage

/** Stable codes own application copy; user text is displayed verbatim, never interpreted as state. */
object DeviceLabels {
    fun text(zh: String, en: String) = if (AppLanguage.current == AppLanguage.CHINESE) zh else en
    fun status(status: DeviceStatus) = when (status) {
        DeviceStatus.QUEUED -> text("准备执行", "Queued")
        DeviceStatus.RUNNING -> text("正在执行", "Running")
        DeviceStatus.AWAITING_SYSTEM -> text("等待系统处理", "Waiting for system")
        DeviceStatus.AWAITING_USER -> text("等待你操作", "Waiting for you")
        DeviceStatus.CANCELLING -> text("正在停止", "Stopping")
        DeviceStatus.SUCCEEDED -> text("已完成", "Completed")
        DeviceStatus.PARTIAL -> text("部分完成", "Partially completed")
        DeviceStatus.FAILED -> text("执行失败", "Failed")
        DeviceStatus.CANCELLED -> text("已停止", "Stopped")
        DeviceStatus.UNCONFIRMED -> text("结果待确认", "Result unconfirmed")
        DeviceStatus.INTERRUPTED -> text("执行被中断", "Interrupted")
    }
    fun title(plugin: String, action: String): String {
        val name = when (plugin) {
            "screen" -> text("屏幕", "Screen"); "sms" -> text("短信", "Messages")
            "contacts" -> text("联系人", "Contacts"); "calendar" -> text("日历", "Calendar")
            "media" -> text("相册与媒体", "Photos and media"); "storage" -> text("文件", "Files")
            "camera" -> text("拍摄照片", "Take photo"); "microphone" -> text("录音", "Record audio")
            "location" -> text("位置", "Location"); "sensors" -> text("传感器", "Sensors")
            "clipboard" -> text("剪贴板", "Clipboard"); "office" -> text("Office 文档", "Office document")
            "appfunction" -> text("应用功能", "App function")
            else -> text("设备任务", "Device task")
        }
        val actionName = when (action) {
            "list", "read", "snapshot", "inspect" -> text("读取", "Read")
            "send" -> text("发送", "Send"); "create" -> text("新建", "Create"); "update" -> text("修改", "Update")
            "delete" -> text("删除", "Delete"); "write" -> text("写入", "Write"); "copy" -> text("复制", "Copy")
            "export" -> text("导出", "Export"); "click", "tap" -> text("点击", "Tap"); "type" -> text("输入", "Type")
            "invoke" -> text("调用", "Invoke")
            "back" -> text("返回", "Back"); "home" -> text("回到桌面", "Home"); "recents" -> text("最近应用", "Recent apps")
            else -> ""
        }
        return if (actionName.isBlank()) name else "$name · $actionName"
    }
    fun phase(operation: DeviceOperation): String = when (operation.phaseCode) {
        "appfunction.confirm" -> text("请核对目标应用与参数后确认调用", "Review target app and arguments before calling")
        "capture.photo" -> text("请拍摄照片", "Take a photo")
        "capture.record" -> text("正在录音，完成后确认素材", "Recording; confirm when ready")
        "capture.confirm" -> text("请确认使用或重新采集", "Confirm or capture again")
        "sms.awaiting_sent_receipt" -> text("已提交，等待发送回执", "Submitted; awaiting sent receipt")
        else -> status(operation.status)
    }
    fun error(code: DeviceErrorCode): String = when (code) {
        DeviceErrorCode.INVALID_ARGUMENT -> text("参数不完整或无效", "Missing or invalid arguments")
        DeviceErrorCode.UNAUTHORIZED -> text("本轮未授权此操作", "Not authorized for this turn")
        DeviceErrorCode.UNSUPPORTED_CAPABILITY -> text("当前不支持此能力", "Capability unavailable")
        DeviceErrorCode.INCOMPATIBLE_VERSION -> text("设备协议版本不匹配", "Incompatible device protocol")
        DeviceErrorCode.REQUEST_CONFLICT -> text("相同请求编号的参数不一致", "Request ID conflicts with previous arguments")
        DeviceErrorCode.STALE_DEVICE_PROMPT -> text("此交互已过期，请查看最新状态", "Conversation expired; check the latest state")
        DeviceErrorCode.NOT_FOUND -> text("未找到该对象", "Object not found")
        DeviceErrorCode.PERMISSION_REVOKED -> text("权限已撤销", "Permission revoked")
        DeviceErrorCode.RESOURCE_MISSING -> text("附件已过期或无法读取", "Attachment expired or unavailable")
        DeviceErrorCode.TIMEOUT -> text("操作超时", "Operation timed out")
        DeviceErrorCode.CANCELLED -> text("操作已停止", "Operation stopped")
        DeviceErrorCode.INTERRUPTED -> text("操作被中断，未自动重试", "Interrupted; not retried automatically")
        DeviceErrorCode.RESULT_UNCONFIRMED -> text("结果尚未确认，请勿重复提交", "Result unconfirmed; do not resubmit")
        DeviceErrorCode.STORAGE_FULL -> text("无法保存操作状态", "Could not persist operation state")
        DeviceErrorCode.PROTOCOL_ERROR -> text("设备结果格式无效", "Invalid device result")
        DeviceErrorCode.UNAVAILABLE -> text("当前无法完成操作", "Operation unavailable")
    }
    fun field(key: String) = when (key) {
        "recipientMasked" -> text("号码尾号", "Number ending"); "recipientLabel" -> text("收件人", "Recipient")
        "body", "text" -> text("内容", "Content"); "query" -> text("目标", "Target"); "name", "title" -> text("名称", "Name")
        "path", "from" -> text("来源", "Source"); "start" -> text("开始", "Start"); "end" -> text("结束", "End")
        "id", "recordId" -> text("记录编号", "Record ID"); "kind" -> text("类型", "Type")
        "sizeBytes" -> text("字节数", "Bytes"); "time", "sampledAtEpochMillis" -> text("时间", "Time")
        "sender" -> text("发送方", "Sender"); "phone" -> text("电话", "Phone"); "source" -> text("来源", "Source")
        "scope" -> text("范围", "Scope"); "timeZone" -> text("时区", "Time zone"); else -> key
    }
    fun scope(code: String) = when (code) {
        "sms.inbox.recent_20" -> text("收件箱最近 20 条以内", "Up to 20 recent inbox messages")
        "contacts.first_50" -> text("前 50 个联系人以内", "Up to 50 contacts")
        "calendar.upcoming_20" -> text("近期 20 个事件以内", "Up to 20 upcoming events")
        "media.authorized_30" -> text("仅已授权媒体，最多 30 项", "Authorized media only; up to 30 items")
        "storage.selected_directory_100" -> text("所选目录单层，最多 100 项", "Selected directory, one level; up to 100 items")
        "storage.selected_directory" -> text("所选目录", "Selected directory")
        "run.imported" -> text("本轮导入的文件", "Files imported in this turn")
        else -> code
    }
}
