package com.github.ytlog.mobby.android.device

import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric

@RunWith(RobolectricTestRunner::class)
class ScreenAdmissionTest {
    @Test fun createdScreenServiceIsAvailableUntilDestroyed() {
        ScreenAccessService.instance = null
        val controller = Robolectric.buildService(ScreenAccessService::class.java)
        controller.create()
        assertTrue(ScreenAccessService.connected())
        controller.destroy()
        assertFalse(ScreenAccessService.connected())
    }

    @Test fun enabledScreenPermissionSurvivesTemporaryServiceDisconnect() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, ScreenAccessService::class.java).flattenToString()
        ScreenAccessService.instance = null
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component)

        assertTrue(DeviceHost.granted(context, "plugin:device:screen"))

        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        assertFalse(DeviceHost.granted(context, "plugin:device:screen"))

        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.example.other/com.example.other.AccessibilityService")
        assertFalse(DeviceHost.granted(context, "plugin:device:screen"))
    }
}
