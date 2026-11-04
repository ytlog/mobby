package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

@Composable private fun ScreenSetupTask(number: Int, title: String, complete: Boolean, focused: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(complete, focused) { mutableStateOf(focused && !complete) }
    SettingsGroup {
        SettingsItem("$number · $title", { expanded = !expanded }, if (complete) AppStrings.stepCompleted else null)
        if (expanded) {
            GroupDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable internal fun AdbSetupGuide(state: ScreenControlState, status: ScreenSetupStatus, enabled: Boolean,
    open: (ScreenSettingsTarget) -> Unit, notifications: () -> Unit, pair: () -> Unit, compact: Boolean = false) {
    var family by remember { mutableStateOf(DeviceSettingsFamily.detect()) }
    var brands by remember { mutableStateOf(false) }
    var notificationHelp by remember { mutableStateOf(false) }
    var showSteps by remember(compact) { mutableStateOf(!compact) }
    if (compact) {
        SettingsGroup { SettingsItem(AppStrings.screenShowSetupSteps, { showSteps = !showSteps }, AppStrings.screenPairedBefore) }
    }
    if (!showSteps) return
    val focus = when {
        state.adbPairing -> 4
        !status.notificationsAllowed -> 1
        status.developerEnabled == false -> 2
        status.wirelessEnabled == false || !status.wifiConnected -> 3
        else -> 4
    }
    Text(AppStrings.screenGuideTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
    SettingsCaption(AppStrings.screenCheckOnReturn)
    ScreenSetupTask(1, AppStrings.screenAllowNotifications, status.notificationsAllowed, focus == 1) {
        Text(AppStrings.screenNotificationHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = notifications, enabled = enabled) { Text(AppStrings.openNotificationSettings) }
    }
    ScreenSetupTask(2, AppStrings.screenDeveloperStep, status.developerEnabled == true, focus == 2) {
        Text(family.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(family.instructions(), style = MaterialTheme.typography.bodyMedium)
        Text(AppStrings.screenDeveloperCommon, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open(ScreenSettingsTarget.ABOUT) }, enabled = enabled) { Text(AppStrings.screenAboutDevice) }
        TextButton(onClick = { brands = !brands }) { Text(AppStrings.screenOtherBrands) }
        if (brands) DeviceSettingsFamily.entries.forEach { candidate ->
            ChoiceRow(candidate.label(), candidate == family, { family = candidate; brands = false })
        }
    }
    ScreenSetupTask(3, AppStrings.screenWirelessStep, status.wirelessEnabled == true && status.wifiConnected, focus == 3) {
        Text(AppStrings.screenWirelessHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!status.wifiConnected) TextButton(onClick = { open(ScreenSettingsTarget.WIFI) }, enabled = enabled) { Text(AppStrings.screenOpenWifi) }
        TextButton(onClick = { open(ScreenSettingsTarget.WIRELESS) }, enabled = enabled) { Text(AppStrings.screenOpenWireless) }
    }
    ScreenSetupTask(4, AppStrings.screenPairStep, state.adbPaired && !state.adbPairing, focus == 4) {
        if (state.adbPaired && !state.adbPairing) Text(AppStrings.screenPairedBefore, style = MaterialTheme.typography.bodyMedium)
        Text(AppStrings.screenPairHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(AppStrings.screenAutoConnectAfterPair, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (family == DeviceSettingsFamily.XIAOMI) {
            TextButton(onClick = { notificationHelp = !notificationHelp }) { Text(AppStrings.screenNoNotificationReply) }
            if (notificationHelp) Text(AppStrings.screenPairXiaomiHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Keep cancellation visible even when an earlier prerequisite needs attention.
    SettingsGroup {
        SettingsAction(if (state.adbPairing) AppStrings.adbCancelPairing else if (state.adbPaired) AppStrings.screenPairAgain else AppStrings.adbStartPairing,
            enabled = enabled) { pair() }
        GroupDivider()
        SettingsAction(AppStrings.openWirelessDebugging, enabled = enabled) { open(ScreenSettingsTarget.WIRELESS) }
    }
}
