package com.github.ytlog.mobby.android.deviceinteraction.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeviceOperationTest {
    private fun operation() = DeviceOperation("operation", "request", 1, "screen", "snapshot", "screen_control",
        DeviceStatus.RUNNING, "screen.snapshot", DeviceSubject(), JsonObject(emptyMap()))
    @Test fun `terminal result cannot be replaced by a late callback`() {
        val previous = operation().copy(status = DeviceStatus.CANCELLED, revision = 2)
        assertFalse(DeviceOperationRules.advances(previous, operation().copy(revision = 3)))
    }
    @Test fun `recovery retains identity and never produces a successful operation`() {
        val result = DeviceOperationRules.stopped(operation(), interrupted = true)
        assertEquals(DeviceStatus.INTERRUPTED, result.status)
        assertEquals(EffectState.UNKNOWN, result.error?.effectState)
        assertEquals("operation", result.operationId)
        assertFalse(DeviceButton.RETRY in result.availableActions)
        assertEquals(result, DeviceOperationRules.stopped(result, true))
    }
    @Test fun `old revision cannot undo current status`() {
        assertFalse(DeviceOperationRules.advances(operation().copy(revision = 3), operation().copy(revision = 2)))
    }
    @Test fun `unknown display and result types retain diagnostics across storage`() {
        val next = operation().copy(displayType = "future-card", result = DeviceResult("future-result", EffectState.NONE,
            buildJsonObject { put("future", "value") }))
        assertEquals(next, DeviceJson.decodeFromString<DeviceOperation>(DeviceJson.encodeToString(next)))
    }
    @Test fun `cancel before dispatch has no external effect`() {
        val stopped = DeviceOperationRules.stopped(operation().copy(status = DeviceStatus.QUEUED), false)
        assertEquals(DeviceStatus.CANCELLED, stopped.status)
        assertEquals(EffectState.NONE, stopped.error?.effectState)
    }
    @Test fun `identity and immutable arguments cannot change with a revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceOperationRules.advances(operation(), operation().copy(revision = 2, requestId = "different"))
        }
    }
}
