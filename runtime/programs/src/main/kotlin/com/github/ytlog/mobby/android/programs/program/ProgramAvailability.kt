package com.github.ytlog.mobby.android.programs.program

import com.github.ytlog.mobby.android.programs.permission.PermissionState

/** Whether a program can work now, separate from whether the user enabled it. Ordered from most fundamental. */
enum class ProgramAvailability(val wire: String) {
    WORKING("working"), PAUSED("paused"), NEEDS_CONFIG("needs_config"), NEEDS_SYSTEM_PERMISSION("needs_system_permission"),
    GLOBAL_SWITCH_OFF("global_switch_off"), MISSING_PROGRAM_PERMISSION("missing_program_permission"), SOURCE_DISCONNECTED("source_disconnected");

    companion object {
        /** [required] are the states of required grants only; optional grants never block a program. */
        fun of(user: UserState, required: Collection<PermissionState>, missingConfig: Collection<String>, sourceDisconnected: Boolean) = when {
            user == UserState.PAUSED -> PAUSED
            PermissionState.SYSTEM_MISSING in required || PermissionState.UNAVAILABLE in required -> NEEDS_SYSTEM_PERMISSION
            PermissionState.GLOBAL_OFF in required -> GLOBAL_SWITCH_OFF
            required.any { it != PermissionState.GRANTED } -> MISSING_PROGRAM_PERMISSION
            missingConfig.isNotEmpty() -> NEEDS_CONFIG
            sourceDisconnected -> SOURCE_DISCONNECTED
            else -> WORKING
        }
    }
}
