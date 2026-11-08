package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSetupEnvironmentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
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
        assertTrue(access.first().getStringExtra(Intent.EXTRA_COMPONENT_NAME)!!.endsWith(".ScreenAccessService"))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, access[1].action)
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
