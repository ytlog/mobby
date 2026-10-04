package com.github.ytlog.mobby.android.device

import android.app.NotificationManager
import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenPermissionTest {
    @Test fun `screen is unavailable with an actionable reason when notifications are disabled`() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, ScreenAccessService::class.java).flattenToString())
        val notifications = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notifications).setNotificationsEnabled(false)

        assertFalse(DeviceHost.granted(context, "plugin:device:screen"))
        assertTrue(DeviceHost.reason(context, "plugin:device:screen").orEmpty().contains("通知"))
    }
}
