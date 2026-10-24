package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.localization.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CapabilityLabelTest {
    @Test fun `program development and attached programs are named in the composer`() {
        assertEquals(AppStrings.programDevelopment, capabilityLabel("plugin:device:programs"))
        assertEquals("${AppStrings.programChip} · header-watch", capabilityLabel("plugin:program:header-watch"))
    }
}
