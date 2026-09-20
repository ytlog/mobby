package com.mobby.interaction.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EventOrderingTest {
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
