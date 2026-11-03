package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

import org.junit.Assert.*
import org.junit.Test

class ScreenModeTest {
    @Test fun `existing installs use current screen and unknown modes are rejected`() {
        assertEquals(ScreenMode.CURRENT, ScreenMode.restore(null))
        assertEquals(listOf(ScreenMode.CURRENT, ScreenMode.ADB, ScreenMode.ROOT), ScreenMode.entries)
        assertThrows(IllegalArgumentException::class.java) { ScreenMode.restore("SHIZUKU") }
        assertThrows(IllegalArgumentException::class.java) { ScreenMode.restore("invalid") }
    }

    @Test fun `virtual backend failures never dispatch to the current screen`() {
        var current = 0
        val failure = IllegalStateException("virtual display disconnected")
        for (mode in ScreenMode.entries.filter { it != ScreenMode.CURRENT }) {
            val result = runCatching {
                selectScreenBackend(mode, { current++; "main" }, { throw failure })
            }
            assertSame(failure, result.exceptionOrNull())
        }
        assertEquals(0, current)
        assertEquals("main", selectScreenBackend(ScreenMode.CURRENT, { current++; "main" }, { error("virtual") }))
        assertEquals(1, current)
    }
}
