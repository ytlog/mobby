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
            "Use this skill to read or control the selected screen. Follow the backend instructions below. In current-screen mode the display stays on for this run and the previous screen timeout returns when the run ends. Snapshot the target app before interacting; in virtual mode use apps and launch first if no app is open. Password fields appear as [secure]. Each successful screen action attempts a screenshot. When result.data.observationRef is present, use the resource command to export its JPEG and inspect it; screenshotStatus reports capture failure without changing the action outcome. Report failures; do not invent controls or success. Do not change the system screen timeout.",
            DeviceAccess.ACCESSIBILITY,
            commands = listOf(
                DeviceCommand("snapshot", "snapshot", "Read the current window before other actions."),
                DeviceCommand("apps", "apps", "List launchable application packages."),
                DeviceCommand("launch", """launch {"package":"com.example.app"}""", "Launch on the selected screen; virtual mode never launches on the main display."),
                DeviceCommand("click", """click {"query":"visible text"}""", "query is at most 200 characters."),
                DeviceCommand("type", """type {"text":"text"}""", "text is at most 2000 characters."),
                DeviceCommand("tap", """tap {"x":"0.5","y":"0.5"}""", "x and y are fractions from 0 to 1."),
                DeviceCommand("swipe", """swipe {"x":"0.5","y":"0.8","toX":"0.5","toY":"0.2"}""", "Coordinates are fractions from 0 to 1 on the selected display."),
                DeviceCommand("back", "back"),
                DeviceCommand("home", "home"),
                DeviceCommand("recents", "recents"),
            ),
        ),
        DevicePluginSpec(
            "programs", AppStrings.programDevelopment, AppStrings.programDevelopmentSummary, CatalogIds.PHONE,
            "Use this skill to write programs that run inside mobby after this chat. Start with catalog: it lists the capabilities that exist on this phone, the SDK and the package rules. Write the program under ~/workspace/programs/<program-id>/, then run validate until it passes and dry-run to check the background logic with simulated events. You cannot install, enable or grant a program; tell the user what it does, which permissions, config and events it needs, and that they install it in Settings > Programs. records reads runs and logs only for programs the user attached to this chat; a program that is not attached is refused even when it is installed and working.",
            DeviceAccess.NONE,
            commands = listOf(
                DeviceCommand("catalog", "catalog", "Read before writing a program."),
                DeviceCommand("validate", """validate {"path":"~/workspace/programs/my-program"}"""),
                DeviceCommand("dry-run", """dry-run {"path":"~/workspace/programs/my-program","events":"[{\"handler\":\"onSchedule\",\"type\":\"time.schedule\",\"payload\":{}}]","timeoutMs":"20000"}""", "events is a JSON array encoded as one string, like the example. Commands and network requests are simulated; storage uses throwaway data."),
                DeviceCommand("records", """records {"programId":"my-program","limit":"50"}""", "Only programs attached to this chat."),
            ),
        ),
        DevicePluginSpec(
            "sms", AppStrings.sms, AppStrings.readMessagesEnableSendingSeparately, CatalogIds.COMMUNICATION,
            "Read recent SMS, or send one when the send grant is enabled. Only result.data.sent=succeeded confirms sending; submission does not confirm sending or delivery.",
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
            "List media the user has allowed, then copy one item into this run's inbox. Do not assume access to the whole library. " +
                "list is newest first, so \"my latest screenshot\" is usually the first image. To read text or details in a photo or screenshot, " +
                "copy it, export it with the resource command, then view the exported image with your own image or file viewing tool and transcribe only what is visible. " +
                "If you cannot see images, say so instead of guessing from the file name.",
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
            "Take one photo. The user previews and confirms the capture. The result contains a durable resourceRef; use the resource command to obtain an inbox path.",
            DeviceAccess.RUNTIME, listOf(android.Manifest.permission.CAMERA),
            commands = listOf(DeviceCommand("photo", "photo")),
        ),
        DevicePluginSpec(
            "microphone", AppStrings.microphone, AppStrings.openTheRecorderToRecordAudio, CatalogIds.PHONE,
            "Record while the capture panel is visible. The user can listen, record again, cancel, or confirm. The result contains a durable resourceRef; use the resource command to obtain an inbox path.",
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
