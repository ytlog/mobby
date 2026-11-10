package com.github.ytlog.mobby.android.device.privileged

import android.accessibilityservice.AccessibilityServiceInfo
import org.junit.Assert.*
import org.junit.Test

class VirtualScreenTreeTest {
    @Test fun `the virtual display reads the same tree as the accessibility service`() {
        // UiAutomationConnection registers with FLAG_INCLUDE_NOT_IMPORTANT_VIEWS, which adds every layout wrapper
        // that the current-screen accessibility service never sees.
        val registered = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        val flags = VirtualScreen.automationFlags(registered)
        assertEquals(0, flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
        assertNotEquals(0, flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)
        assertNotEquals(0, flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS)
    }
}
