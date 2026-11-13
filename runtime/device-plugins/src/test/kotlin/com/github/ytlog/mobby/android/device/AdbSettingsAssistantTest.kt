package com.github.ytlog.mobby.android.device

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdbSettingsAssistantTest {
    @Test fun `recognizes the MagicOS developer settings heading`() {
        assertTrue("开发人员选项" in AdbSettingsAssistant.DEVELOPER_LABELS)
    }

    @Test fun `reads the connection port in the sibling container on MagicOS`() {
        val row = node()
        val labelContainer = node()
        val valueContainer = node()
        val label = node("android:id/title", "IP 地址和端口")
        shadowOf(row).addChild(labelContainer)
        shadowOf(row).addChild(valueContainer)
        shadowOf(labelContainer).addChild(label)
        shadowOf(valueContainer).addChild(node("android:id/summary", "192.0.2.10:37297"))

        assertEquals(37297, AdbSettingsAssistant.connectionPortForLabel(label))
        shadowOf(valueContainer).addChild(node("android:id/summary", "192.0.2.10:37298"))
        assertNull(AdbSettingsAssistant.connectionPortForLabel(label))
    }

    @Test fun `does not use a summary from a different preference`() {
        val row = node()
        val labelContainer = node()
        val unrelatedPreference = node()
        val label = node("android:id/title", "IP 地址和端口")
        shadowOf(row).addChild(labelContainer)
        shadowOf(labelContainer).addChild(label)
        shadowOf(row).addChild(unrelatedPreference)
        shadowOf(unrelatedPreference).addChild(node("android:id/title", "Other setting"))
        shadowOf(unrelatedPreference).addChild(node("android:id/summary", "192.0.2.10:37298"))

        assertNull(AdbSettingsAssistant.connectionPortForLabel(label))
        shadowOf(labelContainer).addChild(node("android:id/summary", "192.0.2.10:37297"))
        assertEquals(37297, AdbSettingsAssistant.connectionPortForLabel(label))
    }

    @Test fun `MagicOS wireless switch can sit outside the title and its parent`() {
        val row = node()
        val titleFrame = node()
        val titleContainer = node()
        val label = node("android:id/title", "无线调试")
        val widgetFrame = node("android:id/widget_frame")
        val switch = node("android:id/switch_widget").apply {
            className = "android.widget.Switch"
            isCheckable = true
            isClickable = true
            isEnabled = true
        }
        shadowOf(row).addChild(titleFrame)
        shadowOf(titleFrame).addChild(titleContainer)
        shadowOf(titleContainer).addChild(label)
        shadowOf(row).addChild(widgetFrame)
        shadowOf(widgetFrame).addChild(switch)

        assertEquals(switch, AdbSettingsAssistant.switchTargetForLabel(label))
        switch.isChecked = true
        assertNull(AdbSettingsAssistant.switchTargetForLabel(label))
    }

    @Test fun `a neighboring preference cannot supply the switch`() {
        val container = node()
        val label = node("android:id/title", "无线调试")
        val otherRow = node().apply { isClickable = true; isEnabled = true }
        shadowOf(container).addChild(label)
        shadowOf(container).addChild(otherRow)
        shadowOf(otherRow).addChild(node("android:id/title", "USB 调试"))
        shadowOf(otherRow).addChild(node("android:id/switch_widget").apply {
            isCheckable = true
            isClickable = true
            isEnabled = true
        })

        assertNull(AdbSettingsAssistant.switchTargetForLabel(label))
    }

    private fun node(id: String? = null, value: String? = null) =
        AccessibilityNodeInfo.obtain(View(RuntimeEnvironment.getApplication())).apply {
            packageName = "com.android.settings"
            viewIdResourceName = id
            text = value
            isVisibleToUser = true
            isEnabled = true
        }
}
