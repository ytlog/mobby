package com.github.ytlog.mobby.android.programcenter

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.PendingInstall
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.ConfigField
import com.github.ytlog.mobby.android.programs.program.ConfigType
import com.github.ytlog.mobby.android.programs.program.InstallPreview
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.RequestedPermission
import com.github.ytlog.mobby.android.programs.program.UserState
import com.github.ytlog.mobby.android.programs.store.RunRow
import kotlinx.serialization.json.*
import java.time.Duration

/** One line under a program's name; [problem] lines are shown as needing attention. */
internal data class StatusLine(val text: String, val problem: Boolean = false)

/** A schedule filter in words, or null when it is not one mobby knows. */
internal fun scheduleLabel(filter: JsonObject): String? {
    filter["every"]?.jsonPrimitive?.contentOrNull?.let { text ->
        val minutes = runCatching { Duration.parse(text).toMinutes() }.getOrNull()?.takeIf { it > 0 } ?: return null
        return when {
            minutes % (24 * 60) == 0L -> AppStrings.everyDays(minutes / (24 * 60))
            minutes % 60 == 0L -> AppStrings.everyHours(minutes / 60)
            else -> AppStrings.everyMinutes(minutes)
        }
    }
    filter["daily"]?.jsonPrimitive?.contentOrNull?.let { return AppStrings.dailyAt(it) }
    filter["at"]?.jsonPrimitive?.contentOrNull?.let { return AppStrings.onceAt(it) }
    return null
}

/** What a requested capability lets the program do, in words; unknown capabilities are shown by id. */
internal fun purposeLabel(capability: String, scope: Scope, schedules: List<JsonObject>): String = when (capability) {
    "net.http" -> AppStrings.purposeNet(scope.domains?.sorted()?.joinToString().orEmpty()) + if (scope.insecureHttp) AppStrings.purposePlainHttp else ""
    "storage.sql" -> AppStrings.purposeSql
    "storage.kv" -> AppStrings.purposeKv
    "storage.files" -> AppStrings.purposeFiles
    "notify.show" -> AppStrings.purposeNotify
    "ai.call" -> AppStrings.purposeAiCall
    "entry.share" -> AppStrings.purposeShare
    "entry.shortcut" -> AppStrings.purposeShortcut
    "time.schedule" -> schedules.mapNotNull(::scheduleLabel).distinct().takeIf { it.isNotEmpty() }
        ?.let { AppStrings.purposeSchedule(it.joinToString("、")) } ?: AppStrings.purposeScheduleAny
    "secrets" -> AppStrings.purposeSecrets
    "clipboard.read" -> AppStrings.purposeClipboardRead
    "clipboard.write" -> AppStrings.purposeClipboardWrite
    "contacts.list" -> AppStrings.purposeContactsList
    "contacts.create" -> AppStrings.purposeContactsCreate
    "contacts.update" -> AppStrings.purposeContactsUpdate
    "contacts.delete" -> AppStrings.purposeContactsDelete
    "sms.list" -> AppStrings.purposeSmsList
    "sms.send" -> AppStrings.purposeSmsSend
    "location.current" -> AppStrings.purposeLocation
    "device.storage.list" -> AppStrings.purposeDeviceStorageList
    "device.storage.copy" -> AppStrings.purposeDeviceStorageCopy
    "device.storage.export" -> AppStrings.purposeDeviceStorageExport
    else -> capability
}

/** A capability's name without its scope, for permission rows, switches and event sources. */
internal fun capabilityName(capability: String): String = when (capability) {
    "net.http" -> AppStrings.purposeNetAny
    "time.schedule" -> AppStrings.purposeScheduleAny
    else -> purposeLabel(capability, Scope(), emptyList())
}

private val USER_STOPS = setOf("paused", "stopped_by_user", "program_updated", "program_deleted", "stop_all")

/**
 * A program's state in words: paused, what is missing, or what it does and how its last run went.
 * [time] formats the run's time; dry runs are not the program's own runs and are ignored by the caller.
 */
internal fun programStatus(program: ProgramView, lastRun: RunRow?, time: (Long) -> String): StatusLine {
    if (program.userState == UserState.PAUSED) return StatusLine(AppStrings.availabilityPaused)
    if (program.availability != ProgramAvailability.WORKING) return StatusLine(availabilityLabel(program.availability), problem = true)
    val what = program.subscriptions.filter { it.event == "time.schedule" }.firstNotNullOfOrNull { scheduleLabel(it.filter) }
        ?: if (program.hasUi) AppStrings.statusOpenToUse else AppStrings.statusWaitingEvents
    val run = lastRun ?: return StatusLine(what)
    val at = time(run.endedAt ?: run.startedAt ?: run.queuedAt)
    return when {
        run.outcome == null -> StatusLine("$what · ${AppStrings.runRunning}")
        run.outcome == "exited" && run.exitCode == 0 -> StatusLine("$what · ${AppStrings.lastRunOk(at)}")
        run.outcome == "killed" && run.reason in USER_STOPS -> StatusLine("$what · ${AppStrings.lastRunStopped(at)}")
        // mobby itself restarted (an update, or the system ending the app); the run's own calls may all have succeeded.
        run.outcome == "interrupted" -> StatusLine("$what · ${AppStrings.lastRunInterrupted(at)}")
        else -> StatusLine("$what · ${AppStrings.lastRunFailed(at)}", problem = true)
    }
}

/** A workspace program waiting on the user, in words. */
internal fun pendingStatus(item: PendingInstall): StatusLine = when (item) {
    is PendingInstall.Rejected -> StatusLine(AppStrings.pendingRejected(item.problems.size), problem = true)
    is PendingInstall.Ready -> readyStatus(item.preview.installedVersion, item.preview.newlyRequested.size)
}

internal fun readyStatus(installedVersion: String?, newlyRequested: Int) = StatusLine(when {
    installedVersion == null -> AppStrings.pendingNew
    newlyRequested > 0 -> AppStrings.pendingUpdateAsks(newlyRequested)
    else -> AppStrings.pendingUpdate
})

/**
 * Which grants the install page asks about. Required ones are part of installing; optional ones still missing can be
 * ticked, and start ticked only when this version asks for them the first time, so a grant the user turned off stays off.
 */
internal class GrantChoices(private val requests: List<RequestedPermission>, private val needsNewGrant: Collection<String>, newlyRequested: Collection<String>) {
    constructor(preview: InstallPreview) : this(preview.pkg.permissions, preview.needsNewGrant, preview.newlyRequested)

    val tickable: Set<String> = requests.filter { !it.required && it.capability in needsNewGrant }.map { it.capability }.toSet()
    val initiallyTicked: Set<String> = tickable.filter { it in newlyRequested }.toSet()

    /** Grants to apply on confirm: required ones still missing, plus the optional ones the user ticked. */
    fun toApply(ticked: Set<String>): Set<String> =
        requests.filter { it.capability in needsNewGrant && (it.required || it.capability in ticked) }.map { it.capability }.toSet()
}

/** Required settings that would still be empty after installing; kept values and defaults need nothing. */
internal fun fieldsToFill(fields: List<ConfigField>, installed: ProgramView?): List<ConfigField> = fields.filter { field ->
    if (!field.required) return@filter false
    val kept = installed?.config?.firstOrNull { it.field.key == field.key && it.field.type == field.type }
    if (field.type == ConfigType.SECRET.wire) kept?.secretSet != true else field.default == null && kept?.value == null
}

/** Typed text for a setting, or null when it is not a valid value of that type. */
internal fun fieldValue(field: ConfigField, text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return when (ConfigType.of(field.type)) {
        ConfigType.NUMBER -> trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?.let { JsonPrimitive(if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong() else it) }
        ConfigType.BOOLEAN -> trimmed.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        ConfigType.STRING, ConfigType.ENUM, ConfigType.PACKAGE -> JsonPrimitive(trimmed)
        ConfigType.SECRET, null -> null
    }
}
