package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal enum class ScreenSettingsTarget { ABOUT, DEVELOPER, WIRELESS, WIFI, NOTIFICATIONS, ACCESSIBILITY }

/** Chooses where a screen access request goes. Only the wizard can change the saved mode. */
internal object ScreenAccessRouting {
    const val REF = "plugin:device:screen"
    private const val EXTRA_CONVERSATION_GRANT = "conversation_screen_grant"

    fun recommendsAdb(state: ScreenControlState?, sdk: Int = Build.VERSION.SDK_INT, conversationGrant: Boolean = false): Boolean =
        sdk >= 33 && state != null && state.mode == ScreenMode.CURRENT && !state.active &&
            (conversationGrant || !state.modeConfigured)

    fun authorizationIntent(context: Context, ref: String, available: Boolean): Intent? {
        if (ref != REF) return null
        val state = ScreenUi.controller?.state?.value
        return if (!available || recommendsAdb(state, conversationGrant = true))
            setupIntent(context, conversationGrant = true)
        else null
    }

    fun setupIntent(context: Context, conversationGrant: Boolean = false) =
        Intent(context, ScreenControlSettingsActivity::class.java).putExtra(EXTRA_CONVERSATION_GRANT, conversationGrant)

    fun isConversationGrant(intent: Intent) = intent.getBooleanExtra(EXTRA_CONVERSATION_GRANT, false)

    /** Try a device's exported shortcut, then a standard Settings destination. No privileged setting writes. */
    fun settingsIntents(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true): List<Intent> = when (target) {
        ScreenSettingsTarget.ABOUT -> listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        ScreenSettingsTarget.DEVELOPER -> listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        ScreenSettingsTarget.WIRELESS -> listOf(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        ScreenSettingsTarget.WIFI -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
        ScreenSettingsTarget.ACCESSIBILITY -> listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, screenAccessibilityComponent(context).flattenToString()),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        ScreenSettingsTarget.NOTIFICATIONS -> {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channels = if (pairing) listOf("screen-adb-pairing", "runtime") else listOf("runtime")
            val blocked = channels.firstOrNull { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
            if (manager.areNotificationsEnabled() && blocked != null) listOf(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, blocked))
            else listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    } + Intent(Settings.ACTION_SETTINGS)

    fun openSettings(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true): Boolean {
        for (intent in settingsIntents(context, target, pairing)) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        return false
    }
}
