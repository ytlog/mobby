package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

internal enum class ScreenSettingsTarget { ABOUT, DEVELOPER, WIRELESS, WIFI, NOTIFICATIONS, ACCESSIBILITY }

/** Chooses where a screen access request goes. Only the wizard can change the saved mode. */
internal object ScreenAccessRouting {
    const val REF = "plugin:device:screen"
    fun recommendsAdb(state: ScreenControlState?, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= 33 && state != null && state.mode == ScreenMode.CURRENT && !state.active

    fun authorizationIntent(context: Context, ref: String, available: Boolean): Intent? {
        if (ref != REF) return null
        val state = ScreenUi.controller?.state?.value
        return if (!available || recommendsAdb(state)) setupIntent(context) else null
    }

    fun setupIntent(context: Context) = Intent(context, ScreenControlSettingsActivity::class.java)

    /** Try a device's exported shortcut, then a standard Settings destination. No privileged setting writes. */
    fun settingsIntents(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true): List<Intent> = when (target) {
        ScreenSettingsTarget.ABOUT -> listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        ScreenSettingsTarget.DEVELOPER -> listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        ScreenSettingsTarget.WIRELESS -> buildList {
            if (DeviceSettingsFamily.detect() == DeviceSettingsFamily.XIAOMI) {
                add(Intent().setClassName("com.android.settings", "com.android.settings.SubSettings")
                    .putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment"))
            }
            add(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
            add(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        ScreenSettingsTarget.WIFI -> listOf(Intent(Settings.ACTION_WIFI_SETTINGS))
        ScreenSettingsTarget.ACCESSIBILITY -> accessibilityIntents(context)
        ScreenSettingsTarget.NOTIFICATIONS -> {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channels = if (pairing) listOf("screen-adb-pairing", "runtime") else listOf("runtime")
            val blocked = channels.firstOrNull { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
            if (manager.areNotificationsEnabled() && blocked != null) listOf(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, blocked))
            else listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    } + Intent(Settings.ACTION_SETTINGS)

    private fun accessibilityIntents(context: Context): List<Intent> {
        val accessibility = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, screenAccessibilityComponent(context).flattenToString())
        val settings = accessibility.resolveActivity(context.packageManager)
        if (settings?.packageName == "com.android.settings" &&
            settings.className == "com.android.settings.accessibility.MiuiAccessibilitySettingsActivity") {
            // This is the list fragment MIUI opens from its Downloaded apps preference.
            val downloadedApps = Intent().setClassName(settings.packageName, "com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment", "com.android.settings.accessibility.InstalledAccessibilityService")
                .putExtra(":settings:show_fragment_title", AppStrings.wizardDownloadedAccessibilityApps)
            return listOf(downloadedApps, details, accessibility)
        }
        return listOf(details, accessibility)
    }

    fun openSettings(context: Context, target: ScreenSettingsTarget, pairing: Boolean = true,
        assistNavigation: Boolean = false, openPairing: Boolean = false): Boolean {
        for (intent in settingsIntents(context, target, pairing)) {
            try {
                context.startActivity(intent)
                if (assistNavigation && (target == ScreenSettingsTarget.WIRELESS || target == ScreenSettingsTarget.ABOUT) &&
                    intent.action != Settings.ACTION_SETTINGS) {
                    assistAdbSettings(target, openPairing, if (openPairing) context.hostActivity()?.componentName else null)
                }
                return true
            } catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        return false
    }

    private tailrec fun Context.hostActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.hostActivity()
        else -> null
    }
}
