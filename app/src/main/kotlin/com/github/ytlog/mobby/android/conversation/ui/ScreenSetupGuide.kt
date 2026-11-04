package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal enum class ScreenSetupStep {
    MODE, NOTIFICATIONS, ACCESSIBILITY, WIFI, DEVELOPER, WIRELESS, PAIR, CONNECT, COMPLETE,
    BRANDS, PAIR_HELP, PORT, DISCONNECT;
    val auxiliary get() = this in setOf(BRANDS, PAIR_HELP, PORT, DISCONNECT)
}

internal fun screenSetupPath(mode: ScreenMode): List<ScreenSetupStep> = when (mode) {
    ScreenMode.CURRENT -> listOf(ScreenSetupStep.MODE, ScreenSetupStep.NOTIFICATIONS, ScreenSetupStep.ACCESSIBILITY, ScreenSetupStep.COMPLETE)
    ScreenMode.ADB -> listOf(ScreenSetupStep.MODE, ScreenSetupStep.NOTIFICATIONS, ScreenSetupStep.WIFI,
        ScreenSetupStep.DEVELOPER, ScreenSetupStep.WIRELESS, ScreenSetupStep.PAIR, ScreenSetupStep.CONNECT, ScreenSetupStep.COMPLETE)
    ScreenMode.ROOT -> listOf(ScreenSetupStep.MODE, ScreenSetupStep.NOTIFICATIONS, ScreenSetupStep.CONNECT, ScreenSetupStep.COMPLETE)
}

internal fun screenStepComplete(step: ScreenSetupStep, state: ScreenControlState, status: ScreenSetupStatus,
    forcePair: Boolean = false, developerConfirmed: Boolean = false, wirelessConfirmed: Boolean = false): Boolean = when (step) {
    ScreenSetupStep.NOTIFICATIONS -> status.notificationsAllowed
    ScreenSetupStep.ACCESSIBILITY -> status.accessibilityEnabled
    ScreenSetupStep.WIFI -> status.wifiConnected
    ScreenSetupStep.DEVELOPER -> status.developerEnabled == true || status.developerEnabled == null && developerConfirmed
    ScreenSetupStep.WIRELESS -> status.wirelessEnabled == true || status.wirelessEnabled == null && wirelessConfirmed
    ScreenSetupStep.PAIR -> state.adbPaired && !state.adbPairing && !forcePair
    ScreenSetupStep.CONNECT -> state.connected
    else -> false
}
