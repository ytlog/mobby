package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSetupEnvironmentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun settingsHandler(intent: Intent, className: String, permission: String? = null) {
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = className
                exported = true
                this.permission = permission
                applicationInfo = ApplicationInfo().apply { flags = ApplicationInfo.FLAG_SYSTEM }
            }
        })
    }
    @Test fun `brand matching handles subbrands and does not confuse Honor with Huawei`() {
        assertEquals(DeviceSettingsFamily.XIAOMI, DeviceSettingsFamily.detect("XIAOMI", "Redmi"))
        assertEquals(DeviceSettingsFamily.XIAOMI, DeviceSettingsFamily.detect("unknown", "POCO"))
        assertEquals(DeviceSettingsFamily.OPPO, DeviceSettingsFamily.detect("OnePlus", "oneplus"))
        assertEquals(DeviceSettingsFamily.VIVO, DeviceSettingsFamily.detect("vivo", "iQOO"))
        assertEquals(DeviceSettingsFamily.SAMSUNG, DeviceSettingsFamily.detect("samsung", "samsung"))
        assertEquals(DeviceSettingsFamily.HONOR, DeviceSettingsFamily.detect("HUAWEI", "HONOR"))
        assertEquals(DeviceSettingsFamily.HUAWEI, DeviceSettingsFamily.detect("huawei", "huawei"))
        assertEquals(DeviceSettingsFamily.ANDROID, DeviceSettingsFamily.detect("unknown", "unknown"))
    }
    @Test fun `missing system flags stay unknown and returning can observe the new values`() {
        assertNull(ScreenSetupStatus.read(context).developerEnabled)
        assertNull(ScreenSetupStatus.read(context).wirelessEnabled)
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        assertTrue(ScreenSetupStatus.read(context).developerEnabled == true)
        assertFalse(ScreenSetupStatus.read(context).wirelessEnabled!!)
        Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        assertTrue(ScreenSetupStatus.read(context).wirelessEnabled == true)
    }
    @Test fun `blocked pairing channel leads to its actual settings and is not reported as ready`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("screen-adb-pairing", "fixture", NotificationManager.IMPORTANCE_NONE))
        assertFalse(ScreenSetupStatus.read(context).notificationsAllowed)
        val intent = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.NOTIFICATIONS).first()
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("screen-adb-pairing", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
    @Test fun `wireless and accessibility shortcuts have standard Settings fallbacks`() {
        val wireless = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.WIRELESS)
        assertEquals("android.settings.WIRELESS_DEBUGGING_SETTINGS", wireless.first().action)
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, wireless[1].action)
        assertEquals(Settings.ACTION_SETTINGS, wireless.last().action)
        val access = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.ACCESSIBILITY)
        assertEquals(listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS), access.map(Intent::getAction))
    }
    @Test fun `MIUI accessibility opens the downloaded service list before the general settings fallback`() {
        settingsHandler(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "com.android.settings.accessibility.MiuiAccessibilitySettingsActivity")
        val intents = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.ACCESSIBILITY)
        val downloadedApps = intents.first()
        assertEquals("com.android.settings.SubSettings", downloadedApps.component?.className)
        assertEquals("com.android.settings.accessibility.InstalledAccessibilityService",
            downloadedApps.getStringExtra(":settings:show_fragment"))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intents[intents.lastIndex - 1].action)
    }
    @Test fun `ColorOS opens downloaded apps before the general accessibility page`() {
        val action = "oplus.intent.action.settings.ACCESSIBILITY_DOWNLOAD"
        settingsHandler(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "com.android.settings.AccessibilitySettingsActivity")
        settingsHandler(Intent(action).setPackage("com.android.settings"), "com.oplus.settings.DownloadedAccessibilitySettings")
        val intents = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.ACCESSIBILITY)
        assertEquals(listOf(action, Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS), intents.map(Intent::getAction))
        assertEquals("com.android.settings", intents.first().component?.packageName)
    }
    @Test fun `protected service details fall back to the system accessibility page`() {
        settingsHandler(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "com.android.settings.AccessibilitySettingsActivity")
        settingsHandler(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").setPackage("com.android.settings"),
            "com.android.settings.AccessibilityDetailsSettingsActivity", "android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS")
        val intents = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.ACCESSIBILITY)
        assertEquals(listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS), intents.map(Intent::getAction))
        assertEquals("com.android.settings", intents.first().`package`)
    }
    @Test fun `an accessible system detail page receives the exact mobby service component`() {
        settingsHandler(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "com.android.settings.AccessibilitySettingsActivity")
        settingsHandler(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").setPackage("com.android.settings"),
            "com.android.settings.AccessibilityDetailsSettingsActivity")
        val intents = ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.ACCESSIBILITY)
        assertEquals("com.android.settings.AccessibilityDetailsSettingsActivity", intents.first().component?.className)
        assertEquals(screenAccessibilityComponent(context).flattenToString(), intents.first().getStringExtra(Intent.EXTRA_COMPONENT_NAME))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intents[1].action)
    }
    @Test fun `current screen mode does not require an unrelated pairing notification channel`() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("screen-adb-pairing", "fixture", NotificationManager.IMPORTANCE_NONE))
        assertTrue(ScreenSetupStatus.read(context, pairing = false).notificationsAllowed)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ScreenAccessRouting.settingsIntents(context, ScreenSettingsTarget.NOTIFICATIONS, pairing = false).first().action)
    }
    @Test fun `unavailable or restricted shortcuts try the next page without claiming success`() {
        val attempts = mutableListOf<String?>()
        val wrapper = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts += intent.action
                if (attempts.size == 1) throw ActivityNotFoundException()
                if (attempts.size == 2) throw SecurityException()
            }
        }
        assertTrue(ScreenAccessRouting.openSettings(wrapper, ScreenSettingsTarget.WIRELESS))
        assertEquals(listOf("android.settings.WIRELESS_DEBUGGING_SETTINGS", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS), attempts)
        val rejected = object : ContextWrapper(context) { override fun startActivity(intent: Intent) { throw ActivityNotFoundException() } }
        assertFalse(ScreenAccessRouting.openSettings(rejected, ScreenSettingsTarget.WIRELESS))
    }
    @Test fun `setup progress resumes and completion does not write other settings`() {
        val preferences = context.getSharedPreferences("screen-control", 0)
        preferences.edit().putString("mode", "ADB").putBoolean("adb-paired", true).commit()
        val progress = SetupProgress(context)
        assertEquals(SetupStep.AGENTS, progress.read())
        progress.save(SetupStep.SCREEN)
        assertEquals(SetupStep.SCREEN, SetupProgress(context).read())
        progress.save(SetupStep.DONE)
        assertEquals(SetupStep.DONE, SetupProgress(context).read())
        assertEquals("ADB", preferences.getString("mode", null))
        assertTrue(preferences.getBoolean("adb-paired", false))
    }
}
