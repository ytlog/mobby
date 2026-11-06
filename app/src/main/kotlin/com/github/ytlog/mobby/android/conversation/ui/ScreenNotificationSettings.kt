package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal fun shouldRecommendAdb(state: ScreenControlState?, sdk: Int = Build.VERSION.SDK_INT): Boolean =
    sdk >= 33 && state != null && state.mode == ScreenMode.CURRENT && !state.modeConfigured && !state.active

internal fun screenSetupIntent(context: Context) = Intent(context, ScreenControlSettingsActivity::class.java)

internal fun screenAccessSettingsIntent(context: Context): Intent {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val enabled = notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel("runtime")?.importance != NotificationManager.IMPORTANCE_NONE
    return if (enabled && (ScreenUi.controller?.state?.value?.mode
            ?: com.github.ytlog.mobby.android.runtime.api.device.ScreenMode.CURRENT) != com.github.ytlog.mobby.android.runtime.api.device.ScreenMode.CURRENT)
        screenSetupIntent(context)
    else if (enabled) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
