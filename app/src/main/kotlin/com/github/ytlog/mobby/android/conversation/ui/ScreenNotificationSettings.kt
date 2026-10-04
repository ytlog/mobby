package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal fun screenAccessSettingsIntent(context: Context): Intent {
    val notifications = context.getSystemService(NotificationManager::class.java)
    val enabled = notifications.areNotificationsEnabled() &&
        notifications.getNotificationChannel("runtime")?.importance != NotificationManager.IMPORTANCE_NONE
    return if (enabled) Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}
