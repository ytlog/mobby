package com.github.ytlog.mobby.android.interaction.domain

import com.github.ytlog.mobby.android.deviceinteraction.model.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EventOrderingTest {
    @Test fun `screen actions share one card without losing earlier outcomes`() {
        fun record(id: String, status: DeviceStatus, order: Long) = DeviceRecord(DeviceOperation(id, id, 1, "screen", "snapshot", "screen_control",
            status, "snapshot", DeviceSubject(), JsonObject(emptyMap())), id, order)
        val turn = Turn(TurnId("turn"), "look", ExecutionId("run"), ExecutionPhase.RUNNING, occupied = true,
            deviceOperations = listOf(record("first", DeviceStatus.FAILED, 1), record("second", DeviceStatus.RUNNING, 3)))
        val card = turn.visibleTranscript().filterIsInstance<TranscriptEntry.Device>().single()
        assertEquals("second", card.record.operation.operationId)
        assertEquals(listOf(DeviceStatus.FAILED, DeviceStatus.RUNNING), card.history.map { it.operation.status })
        assertEquals("first", card.history.first().operation.operationId)
    }
    @Test fun `duplicate and out of order output is not appended`() {
        val id = ExecutionId("run")
        var cursor = ProjectionCursor(id, 1)
        val text = StringBuilder()
        for ((sequence, chunk) in listOf(2L to "hello", 2L to "hello", 4L to "!", 3L to " world", 4L to "!")) {
            if (cursor.classify(id, sequence) == EventOrdering.APPLY) {
                text.append(chunk)
                cursor = ProjectionCursor(id, sequence)
            }
        }
        assertEquals("hello world!", text.toString())
        assertEquals(4L, cursor.sequence)
        assertEquals(EventOrdering.WRONG_EXECUTION, cursor.classify(ExecutionId("another"), 5))
        assertEquals(EventOrdering.GAP, cursor.classify(id, 6))
    }
}
