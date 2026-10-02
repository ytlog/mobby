package com.github.ytlog.mobby.android.device

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScreenAccessibilityLabelTest {
    @Test @Config(qualifiers = "zh-rCN")
    fun `system accessibility list identifies mobby screen control`() {
        assertEquals("mobby 屏幕操作", RuntimeEnvironment.getApplication().getString(R.string.screen_accessibility_label))
    }

    @Test @Config(qualifiers = "en")
    fun `english accessibility list identifies the app`() {
        assertEquals("mobby Screen Control", RuntimeEnvironment.getApplication().getString(R.string.screen_accessibility_label))
    }
}
