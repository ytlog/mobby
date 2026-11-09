package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*

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

    private class LostWorker {
        var alive = true
        var connected = true
        val calls = mutableListOf<Pair<Int, String>>()
        val lost = IllegalStateException("connection lost after dispatch")
        fun worker(display: Int) = object : ScreenSession {
            override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation {
                calls += display to action
                if (display == 7) { alive = false; connected = false; throw lost }
                return ScreenAccessService.Observation("", "", 0L, ScreenScreenshot(status = "not_requested"), "adb", display)
            }
            override fun inspect(query: String?, expectedInput: String?): ScreenEvidence = error("unexpected inspect")
            override fun close() = Unit
        }
        // Nothing else reconnects during a task, so a recovery that only waits must fail this guard.
        var waits = 0
        val checkActive: () -> Unit = { check(++waits < 50) { "recovery waited without reconnecting" } }
    }

    @Test fun `a lost ADB connection reconnects with the saved pairing during the same task`() {
        val lost = LostWorker()
        var reconnects = 0
        val session = RecoveringAdbScreenSession(lost.worker(7), { lost.alive }, { lost.connected },
            reconnect = { reconnects++; lost.connected = true }) {
            lost.alive = true
            lost.worker(8)
        }
        assertSame(lost.lost, runCatching { session.operate("tap", emptyMap(), lost.checkActive) }.exceptionOrNull())
        assertEquals(1, reconnects)
        session.operate("snapshot", emptyMap(), lost.checkActive)
        assertEquals(listOf(7 to "tap", 8 to "snapshot"), lost.calls)
    }

    @Test fun `unreachable wireless debugging ends recovery at once with its reason`() {
        val lost = LostWorker()
        val unreachable = DeviceFailure(DeviceError(DeviceErrorCode.UNAVAILABLE, "wireless debugging unreachable"))
        var reconnects = 0
        val session = RecoveringAdbScreenSession(lost.worker(7), { lost.alive }, { lost.connected },
            reconnect = { reconnects++; throw unreachable }) { error("must not reopen without a connection") }
        assertSame(unreachable, runCatching { session.operate("tap", emptyMap(), lost.checkActive) }.exceptionOrNull())
        assertEquals(1, reconnects)
        // Turning wireless debugging back on lets the next action of the same task reconnect.
        val failed = runCatching { session.operate("snapshot", emptyMap(), lost.checkActive) }.exceptionOrNull()
        assertSame(unreachable, failed)
        assertEquals(2, reconnects)
    }

    @Test fun `a reconnect that keeps failing times out with the last reason`() {
        val lost = LostWorker()
        val session = RecoveringAdbScreenSession(lost.worker(7), { lost.alive }, { lost.connected },
            reconnect = { throw IllegalStateException("endpoint not found") }, reconnectTimeoutMillis = 300) { error("unexpected reopen") }
        val failure = runCatching { session.operate("tap", emptyMap()) {} }.exceptionOrNull()
        assertTrue(failure.toString(), failure is DeviceFailure)
        val error = (failure as DeviceFailure).error
        assertEquals(DeviceErrorCode.TIMEOUT, error.code)
        assertTrue(error.message, error.message.contains("endpoint not found"))
    }
}
