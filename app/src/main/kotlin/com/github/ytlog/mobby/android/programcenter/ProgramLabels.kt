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
