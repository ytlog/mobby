package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.CatalogIds

import com.github.ytlog.mobby.android.localization.AppStrings

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
    val all get() = listOf(
        DevicePluginSpec(
            "screen", AppStrings.screen, AppStrings.readAndInteractWithTheCurrentScreen, CatalogIds.PHONE,
            "Use this skill to read or control the current phone. The display stays on for this run and the previous screen timeout returns when the run ends. Always snapshot first. Password fields appear as [secure]. Report failures; do not invent controls or success. Do not change the system screen timeout.",
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
            "sms", AppStrings.sms, AppStrings.readMessagesEnableSendingSeparately, CatalogIds.COMMUNICATION,
            "Read recent SMS, or send one when the send grant is enabled. Do not claim a message was sent unless the command succeeds.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_SMS),
            DeviceGrantSpec("send", AppStrings.send, listOf(android.Manifest.permission.SEND_SMS), setOf("send")),
            listOf(
                DeviceCommand("list", "list", "Returns about 20 recent inbox messages."),
                DeviceCommand("send", """send {"to":"+8613800138000","body":"text"}""", "body is at most 500 characters.", grant = true),
            ),
        ),
        DevicePluginSpec(
            "contacts", AppStrings.contacts, AppStrings.readContactsEnableEditingSeparately, CatalogIds.COMMUNICATION,
            "List contacts, or create, update and delete them when the write grant is enabled.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_CONTACTS),
            DeviceGrantSpec("write", AppStrings.edit, listOf(android.Manifest.permission.WRITE_CONTACTS), setOf("create", "update", "delete")),
            listOf(
                DeviceCommand("list", "list", "Returns up to 50 contacts."),
                DeviceCommand("create", """create {"name":"Ada","phone":"13800138000"}""", grant = true),
                DeviceCommand("update", """update {"id":"1","name":"Ada"}""", grant = true),
                DeviceCommand("delete", """delete {"id":"1"}""", grant = true),
            ),
        ),
        DevicePluginSpec(
            "calendar", AppStrings.calendar, AppStrings.readEventsEnableEditingSeparately, CatalogIds.COMMUNICATION,
            "List upcoming events, or create, update and delete them when the write grant is enabled.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.READ_CALENDAR),
            DeviceGrantSpec("write", AppStrings.edit, listOf(android.Manifest.permission.WRITE_CALENDAR), setOf("create", "update", "delete")),
            listOf(
                DeviceCommand("list", "list", "Returns up to 20 upcoming events."),
                DeviceCommand("create", """create {"title":"Meet","start":"1710000000000","end":"1710003600000"}""", "start and end are epoch milliseconds.", grant = true),
                DeviceCommand("update", """update {"id":"1","title":"Meet"}""", grant = true),
                DeviceCommand("delete", """delete {"id":"1"}""", grant = true),
            ),
        ),
        DevicePluginSpec(
            "media", AppStrings.media, AppStrings.readAuthorizedPhotosVideosAndAudio, CatalogIds.FILES,
            "List media the user has allowed, then copy one item into this run's inbox. Do not assume access to the whole library.",
            DeviceAccess.RUNTIME,
            commands = listOf(
                DeviceCommand("list", "list"),
                DeviceCommand("copy", """copy {"kind":"image","id":"1"}""", "kind is image, video or audio."),
            ),
        ),
        DevicePluginSpec(
            "storage", AppStrings.storage, AppStrings.accessADirectoryYouSelectIncludingOnAnSd, CatalogIds.FILES,
            "Use only the directory the user selected. list and copy read it. export writes a workspace or inbox file back into that directory.",
            DeviceAccess.DOCUMENT_TREE,
            commands = listOf(
                DeviceCommand("list", "list", "One level, up to 100 entries."),
                DeviceCommand("copy", """copy {"name":"notes.txt"}""", "Copies one child file into the inbox."),
                DeviceCommand("export", """export {"from":"notes.txt","name":"notes.txt"}""", "from is a workspace-relative path or an allowed absolute path."),
            ),
        ),
        DevicePluginSpec(
            "camera", AppStrings.camera, AppStrings.openTheViewfinderToTakeAPhoto, CatalogIds.PHONE,
            "Take one photo. The user sees the shutter. The result is a file path in this run's inbox.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.CAMERA),
            commands = listOf(DeviceCommand("photo", "photo")),
        ),
        DevicePluginSpec(
            "microphone", AppStrings.microphone, AppStrings.openTheRecorderToRecordAudio, CatalogIds.PHONE,
            "Record from the microphone while the recording screen is visible. The result is a file path in this run's inbox.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.RECORD_AUDIO),
            commands = listOf(DeviceCommand("record", "record", "The user stops the recording. It also stops after 60 seconds.")),
        ),
        DevicePluginSpec(
            "location", AppStrings.location, AppStrings.getYourLocationOnceWhileTheAppIsIn, CatalogIds.PHONE,
            "Read the current location once while mobby is in the foreground. Do not start tracking.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION),
            commands = listOf(DeviceCommand("current", "current")),
        ),
        DevicePluginSpec(
            "sensors", AppStrings.sensors, AppStrings.readMotionAndEnvironmentalSensorsOnce, CatalogIds.PHONE,
            "Sample accelerometer, gyroscope, magnetometer, light, proximity and pressure once when the device has them.",
            DeviceAccess.NONE,
            commands = listOf(DeviceCommand("sample", "sample")),
        ),
        DevicePluginSpec(
            "clipboard", AppStrings.clipboard, AppStrings.readPlainTextEnableWritingSeparately, CatalogIds.PHONE,
            "Read or write plain text on the clipboard. Reading requires mobby to be in the foreground.",
            DeviceAccess.NONE,
            grant = DeviceGrantSpec("write", AppStrings.write, emptyList(), setOf("write")),
            commands = listOf(
                DeviceCommand("read", "read"),
                DeviceCommand("write", """write {"text":"text"}""", "text is at most 4000 characters.", grant = true),
            ),
        ),
        DevicePluginSpec(
            "office", AppStrings.officeDocuments, AppStrings.readAndWriteXlsxDocxAndPptxFilesIn, CatalogIds.FILES,
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
