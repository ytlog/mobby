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
    @Test fun `ADB reconnect never repeats a dispatched click and requires a fresh observation`() {
        val calls = mutableListOf<Pair<Int, String>>()
        var alive = true
        var connected = true
        val failure = IllegalStateException("connection lost after dispatch")
        fun worker(display: Int) = object : ScreenSession {
            override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation {
                calls += display to action
                if (display == 7) {
                    alive = false
                    connected = false
                    throw failure
                }
                return ScreenAccessService.Observation("", "", 0L, ScreenScreenshot(status = "not_requested"), "adb", display)
            }
            override fun inspect(query: String?, expectedInput: String?): ScreenEvidence = error("unexpected inspect")
            override fun close() = Unit
        }
        val session = RecoveringAdbScreenSession(worker(7), { alive }, { connected }) {
            alive = true
            worker(8)
        }
        var waited = false
        val interrupted = runCatching {
            session.operate("tap", emptyMap()) {
                if (!alive) { waited = true; connected = true }
            }
        }
        assertSame(failure, interrupted.exceptionOrNull())
        assertTrue(waited)
        assertThrows(com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure::class.java) {
            session.operate("tap", emptyMap()) {}
        }
        session.operate("snapshot", emptyMap()) {}
        session.operate("tap", emptyMap()) {}
        assertEquals(listOf(7 to "tap", 8 to "snapshot", 8 to "tap"), calls)
    }
}
