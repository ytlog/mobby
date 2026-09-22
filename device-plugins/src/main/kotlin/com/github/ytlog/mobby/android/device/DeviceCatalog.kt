package com.github.ytlog.mobby.android.device

enum class DeviceAccess { NONE, RUNTIME, ACCESSIBILITY, DOCUMENT_TREE }

data class DeviceCommand(val action: String, val usage: String, val note: String = "", val grant: Boolean = false)

data class DeviceGrantSpec(val suffix: String, val label: String, val permissions: List<String>, val actions: Set<String>)

data class DevicePluginSpec(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val instructions: String,
    val access: DeviceAccess,
    val permissions: List<String> = emptyList(),
    val grant: DeviceGrantSpec? = null,
    val commands: List<DeviceCommand>,
) {
    val ref = "plugin:device:$id"
    val skillName = "mobby-$id"
    val grantRef = grant?.let { "$ref:${it.suffix}" }
    val baseActions = commands.filterNot { it.grant }.map { it.action }.toSet()
}

object DeviceCatalog {
    val all = listOf(
        DevicePluginSpec(
            "screen", "屏幕", "读取并操作当前屏幕", "手机",
            "Use this skill to read or control the current phone. Always snapshot first. Password fields appear as [secure]. Report failures; do not invent controls or success.",
            DeviceAccess.ACCESSIBILITY,
            commands = listOf(
                DeviceCommand("snapshot", "snapshot", "Read the current window before other actions."),
                DeviceCommand("click", """click {"query":"visible text"}""", "query is at most 200 characters."),
                DeviceCommand("type", """type {"text":"text"}""", "text is at most 2000 characters."),
                DeviceCommand("tap", """tap {"x":"0.5","y":"0.5"}""", "x and y are fractions from 0 to 1."),
                DeviceCommand("back", "back"),
                DeviceCommand("home", "home"),
                DeviceCommand("recents", "recents"),
            ),
        ),
        DevicePluginSpec(
            "sms", "短信", "读取短信。发送要单独打开", "沟通",
            "Read recent SMS, or send one when the send grant is enabled. Do not claim a message was sent unless the command succeeds.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_SMS),
            DeviceGrantSpec("send", "发送", listOf(android.Manifest.permission.SEND_SMS), setOf("send")),
            listOf(
                DeviceCommand("list", "list", "Returns about 20 recent inbox messages."),
                DeviceCommand("send", """send {"to":"+8613800138000","body":"text"}""", "body is at most 500 characters.", grant = true),
            ),
        ),
        DevicePluginSpec(
            "contacts", "通讯录", "读取联系人。修改要单独打开", "沟通",
            "List contacts, or create, update and delete them when the write grant is enabled.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_CONTACTS),
            DeviceGrantSpec("write", "修改", listOf(android.Manifest.permission.WRITE_CONTACTS), setOf("create", "update", "delete")),
            listOf(
                DeviceCommand("list", "list", "Returns up to 50 contacts."),
                DeviceCommand("create", """create {"name":"Ada","phone":"13800138000"}""", grant = true),
                DeviceCommand("update", """update {"id":"1","name":"Ada"}""", grant = true),
                DeviceCommand("delete", """delete {"id":"1"}""", grant = true),
            ),
        ),
        DevicePluginSpec(
            "calendar", "日历", "读取日程。修改要单独打开", "沟通",
            "List upcoming events, or create, update and delete them when the write grant is enabled.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_CALENDAR),
            DeviceGrantSpec("write", "修改", listOf(android.Manifest.permission.WRITE_CALENDAR), setOf("create", "update", "delete")),
            listOf(
                DeviceCommand("list", "list", "Returns up to 20 upcoming events."),
                DeviceCommand("create", """create {"title":"Meet","start":"1710000000000","end":"1710003600000"}""", "start and end are epoch milliseconds.", grant = true),
                DeviceCommand("update", """update {"id":"1","title":"Meet"}""", grant = true),
                DeviceCommand("delete", """delete {"id":"1"}""", grant = true),
            ),
        ),
        DevicePluginSpec(
            "media", "相册", "读取已授权的照片、视频和音频", "文件",
            "List media the user has allowed, then copy one item into this run's inbox. Do not assume access to the whole library.",
            DeviceAccess.RUNTIME,
            commands = listOf(
                DeviceCommand("list", "list"),
                DeviceCommand("copy", """copy {"kind":"image","id":"1"}""", "kind is image, video or audio."),
            ),
        ),
        DevicePluginSpec(
            "storage", "存储", "访问用户选定的一个目录，包括 SD 卡", "文件",
            "Use only the directory the user selected. list and copy read it. export writes a workspace or inbox file back into that directory.",
            DeviceAccess.DOCUMENT_TREE,
            commands = listOf(
                DeviceCommand("list", "list", "One level, up to 100 entries."),
                DeviceCommand("copy", """copy {"name":"notes.txt"}""", "Copies one child file into the inbox."),
                DeviceCommand("export", """export {"from":"notes.txt","name":"notes.txt"}""", "from is a workspace-relative path or an allowed absolute path."),
            ),
        ),
        DevicePluginSpec(
            "camera", "相机", "打开取景界面拍一张照片", "手机",
            "Take one photo. The user sees the shutter. The result is a file path in this run's inbox.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.CAMERA),
            commands = listOf(DeviceCommand("photo", "photo")),
        ),
        DevicePluginSpec(
            "microphone", "麦克风", "打开录音界面录一段声音", "手机",
            "Record from the microphone while the recording screen is visible. The result is a file path in this run's inbox.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.RECORD_AUDIO),
            commands = listOf(DeviceCommand("record", "record", "The user stops the recording. It also stops after 60 seconds.")),
        ),
        DevicePluginSpec(
            "location", "位置", "读取一次前台位置", "手机",
            "Read the current location once while mobby is in the foreground. Do not start tracking.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION),
            commands = listOf(DeviceCommand("current", "current")),
        ),
        DevicePluginSpec(
            "sensors", "传感器", "读取一次运动和环境传感器", "手机",
            "Sample accelerometer, gyroscope, magnetometer, light, proximity and pressure once when the device has them.",
            DeviceAccess.NONE,
            commands = listOf(DeviceCommand("sample", "sample")),
        ),
        DevicePluginSpec(
            "clipboard", "剪贴板", "读取纯文本。写入要单独打开", "手机",
            "Read or write plain text on the clipboard. Reading requires mobby to be in the foreground.",
            DeviceAccess.NONE,
            grant = DeviceGrantSpec("write", "写入", emptyList(), setOf("write")),
            commands = listOf(
                DeviceCommand("read", "read"),
                DeviceCommand("write", """write {"text":"text"}""", "text is at most 4000 characters.", grant = true),
            ),
        ),
        DevicePluginSpec(
            "office", "Office 文档", "读写工作区里的 xlsx、docx 和 pptx", "文件",
            "Inspect, read and write xlsx, docx and pptx in the workspace or this run's inbox. Do not execute macros. Paths stay inside those directories.",
            DeviceAccess.NONE,
            commands = listOf(
                DeviceCommand("inspect", """inspect {"path":"notes.docx"}"""),
                DeviceCommand("read", """read {"path":"notes.docx"}"""),
                DeviceCommand("write", """write {"path":"notes.docx","text":"hello"}""", "Writes a simple document. Existing files with the same path are replaced only when the command succeeds."),
            ),
        ),
    )

    fun isKnown(ref: String) = all.any { it.ref == ref || it.grantRef == ref }
    fun missingParent(refs: Set<String>) = all.any { it.grantRef != null && it.grantRef in refs && it.ref !in refs }
    fun allow(refs: Set<String>): Map<String, Set<String>> = buildMap {
        for (spec in all) {
            val actions = mutableSetOf<String>()
            if (spec.ref in refs) actions += spec.baseActions
            if (spec.grantRef != null && spec.grantRef in refs) actions += spec.grant!!.actions
            if (actions.isNotEmpty()) put(spec.id, actions)
        }
    }
    fun selected(refs: Set<String>) = all.filter { it.ref in refs || (it.grantRef != null && it.grantRef in refs) }
}
