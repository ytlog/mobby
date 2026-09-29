package com.github.ytlog.mobby.android.conversation.domain

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EventOrderingTest {
    @Test fun `screen actions retain their own places between thinking and replies`() {
        fun record(id: String, status: DeviceStatus, order: Long) = DeviceRecord(DeviceOperation(id, id, 1, "screen", "snapshot", "screen_control",
            status, "snapshot", DeviceSubject(), JsonObject(emptyMap())), id, order)
        val turn = Turn(TurnId("turn"), "look", ExecutionId("run"), ExecutionPhase.SUCCEEDED,
            messages = listOf(Message("reply", "done", 4)), steps = listOf(Step.Thinking("think", "checking", "SUCCEEDED", 2)),
            deviceOperations = listOf(record("first", DeviceStatus.FAILED, 1), record("second", DeviceStatus.SUCCEEDED, 3)))
        val entries = turn.transcript()
        assertEquals("first", (entries[0] as TranscriptEntry.Device).record.operation.operationId)
        assertEquals("think", (entries[1] as TranscriptEntry.ToolRun).steps.single().id)
        assertEquals("second", (entries[2] as TranscriptEntry.Device).record.operation.operationId)
        assertEquals("reply", (entries[3] as TranscriptEntry.Reply).message.id)
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
