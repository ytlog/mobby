package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.EffectState

import android.content.ComponentName
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun restore() { ScreenControl.select(context, ScreenMode.CURRENT) }

    @Test fun `a saved virtual mode cannot use current screen permission as a fallback`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(context, ScreenAccessService::class.java).flattenToString())
        ScreenControl.select(context, ScreenMode.CURRENT)
        assertTrue(DeviceHost.granted(context, "plugin:device:screen"))
        for (mode in ScreenMode.entries.filter { it != ScreenMode.CURRENT }) {
            ScreenControl.select(context, mode)
            ScreenControl.initialize(context)
            assertEquals(mode, ScreenControl.state.value.mode)
            assertFalse(DeviceHost.granted(context, "plugin:device:screen"))
            assertThrows(IllegalStateException::class.java) { ScreenControl.open(context) }
        }
        ScreenControl.select(context, ScreenMode.CURRENT)
        assertTrue(DeviceHost.granted(context, "plugin:device:screen"))
    }

    @Test fun `app discovery is read only and launch validates the package before dispatch`() {
        assertFalse(DeviceActionDefinition("screen", "apps").writes)
        assertTrue(DeviceActionDefinition("screen", "launch").writes)
        assertThrows(com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure::class.java) {
            DeviceActionDefinition("screen", "launch").validate(kotlinx.serialization.json.buildJsonObject {
                put("package", kotlinx.serialization.json.JsonPrimitive("com.example; input tap 1 1"))
            })
        }
    }

    @Test fun `manual control rejects reads and writes and handback first requires a fresh snapshot`() {
        val manual = ScreenControlState(mode = ScreenMode.ADB, active = true, displayId = 7, manualControlId = "fixture")
        for (action in listOf("snapshot", "inspect", "tap", "request_input")) {
            assertEquals(EffectState.NONE, assertThrows(DeviceFailure::class.java) {
                manual.requireAgentScreenAccess(action)
            }.error.effectState)
        }
        val returned = manual.copy(manualControlId = null, readRequiredAfterHandoff = true)
        assertThrows(DeviceFailure::class.java) { returned.requireAgentScreenAccess("tap") }
        returned.requireAgentScreenAccess("snapshot")
        returned.copy(readRequiredAfterHandoff = false).requireAgentScreenAccess("tap")
    }
}
