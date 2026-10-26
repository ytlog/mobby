package com.github.ytlog.mobby.android.programcenter

import android.content.Context
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.permission.PermissionState
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import java.text.DateFormat
import java.util.Date

internal fun availabilityLabel(value: ProgramAvailability) = when (value) {
    ProgramAvailability.WORKING -> AppStrings.availabilityWorking
    ProgramAvailability.PAUSED -> AppStrings.availabilityPaused
    ProgramAvailability.NEEDS_CONFIG -> AppStrings.availabilityNeedsConfig
    ProgramAvailability.NEEDS_SYSTEM_PERMISSION -> AppStrings.availabilityNeedsSystem
    ProgramAvailability.GLOBAL_SWITCH_OFF -> AppStrings.availabilityGlobalOff
    ProgramAvailability.MISSING_PROGRAM_PERMISSION -> AppStrings.availabilityMissingPermission
    ProgramAvailability.SOURCE_DISCONNECTED -> AppStrings.availabilitySourceDisconnected
}

internal fun permissionLabel(value: PermissionState) = when (value) {
    PermissionState.GRANTED -> AppStrings.permissionGranted
    PermissionState.UNAVAILABLE -> AppStrings.permissionUnavailable
    PermissionState.SYSTEM_MISSING -> AppStrings.permissionSystemMissing
    PermissionState.GLOBAL_OFF -> AppStrings.permissionGlobalOff
    PermissionState.PROGRAM_OFF -> AppStrings.permissionProgramOff
    PermissionState.EXPIRED -> AppStrings.permissionExpired
}

/** Run outcomes, triggers and stop reasons are wire values; unknown ones are shown as written. */
internal fun runOutcomeLabel(outcome: String?) = when (outcome) {
    null -> AppStrings.runRunning
    "exited" -> AppStrings.runExited
    "failed" -> AppStrings.runFailed
    "killed" -> AppStrings.runKilled
    "killed_by_system" -> AppStrings.runKilledBySystem
    "dropped" -> AppStrings.runDropped
    "interrupted" -> AppStrings.runInterrupted
    else -> outcome
}

internal fun runTriggerLabel(trigger: String) = when (trigger) {
    "ui" -> AppStrings.triggerUi
    "schedule" -> AppStrings.triggerSchedule
    "dry_run" -> AppStrings.triggerDryRun
    else -> trigger
}

internal fun runReasonLabel(reason: String) = when (reason) {
    "idle" -> AppStrings.reasonIdle
    "paused" -> AppStrings.reasonPaused
    "stopped_by_user" -> AppStrings.reasonStoppedByUser
    "program_updated" -> AppStrings.reasonProgramUpdated
    "program_deleted" -> AppStrings.reasonProgramDeleted
    "start_timeout" -> AppStrings.reasonStartTimeout
    "launch_failed" -> AppStrings.reasonLaunchFailed
    "exited_before_connecting" -> AppStrings.reasonExitedBeforeConnecting
    "process_restarted" -> AppStrings.reasonProcessRestarted
    "dry_run_timeout" -> AppStrings.reasonDryRunTimeout
    "runtime_update_pending" -> AppStrings.reasonRuntimeUpdatePending
    "runtime_not_ready" -> AppStrings.reasonRuntimeNotReady
    "runtime_not_installed" -> AppStrings.reasonRuntimeNotInstalled
    else -> reason
}

/** Scope entries for display: package labels when visible, domains and methods as written. */
internal fun scopeSummary(context: Context, scope: Scope): String = listOfNotNull(
    scope.packages?.joinToString { appLabel(context, it) },
    scope.domains?.joinToString(),
    scope.methods?.let { AppStrings.methods(it.sorted().joinToString()) },
    scope.models?.joinToString(),
).joinToString(" · ")

internal fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

internal fun appLabel(context: Context, packageName: String): String = runCatching {
    val manager = context.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
}.getOrNull()?.takeIf { it.isNotBlank() && it != packageName } ?: packageName
