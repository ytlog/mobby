package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun AppUpdatePage(updates: AppUpdateManager, back: () -> Unit) {
    val state = updates.state
    Column(Modifier.fillMaxSize()) {
        PageHeader(UiStrings.appUpdates, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup { SettingsToggle(UiStrings.autoCheckUpdates, updates.autoCheck) { updates.setAutoCheck(it) } }
            SettingsCaption(UiStrings.updateSettingSummary)
            SettingsGroup {
                SettingsAction(UiStrings.checkForUpdates, state.phase !in setOf(AppUpdateManager.Phase.CHECKING, AppUpdateManager.Phase.DOWNLOADING)) {
                    updates.check()
                }
                if (state.phase == AppUpdateManager.Phase.READY || state.phase == AppUpdateManager.Phase.NEEDS_PERMISSION) {
                    GroupDivider()
                    SettingsAction(UiStrings.installUpdate) { updates.install() }
                }
            }
            SettingsCaption(when (state.phase) {
                AppUpdateManager.Phase.IDLE -> UiStrings.updateNotChecked
                AppUpdateManager.Phase.CHECKING -> UiStrings.checkingForUpdates
                AppUpdateManager.Phase.CURRENT -> UiStrings.appIsCurrent
                AppUpdateManager.Phase.DOWNLOADING -> UiStrings.downloadingUpdate(state.bytes, state.total)
                AppUpdateManager.Phase.READY -> UiStrings.updateAvailable(state.version.orEmpty())
                AppUpdateManager.Phase.INSTALLING -> UiStrings.waitForSystemInstaller
                AppUpdateManager.Phase.NEEDS_PERMISSION -> UiStrings.allowUpdateInstall
                AppUpdateManager.Phase.ERROR -> UiStrings.updateFailed(state.message.orEmpty())
            }, error = state.phase == AppUpdateManager.Phase.ERROR)
            Text(UiStrings.currentAppVersion(androidx.compose.ui.platform.LocalContext.current.packageManager
                .getPackageInfo(androidx.compose.ui.platform.LocalContext.current.packageName, 0).versionName.orEmpty()))
        }
    }
}
