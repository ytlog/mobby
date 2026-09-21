package com.mobby.interaction.ui

import org.junit.Assert.*
import org.junit.Test

class StreamEngineTest {
    @Test fun `typewriter lags the source and markdown waits for the parse interval`() {
        val engine = StreamEngine(parseIntervalMs = 1_000, charsPerSecond = 10)
        val first = engine.frame("hello world", 0, reducedMotion = false, dtMs = 100)
        assertTrue(first.cursor)
        assertTrue(first.markdown.isEmpty())
        assertEquals("he", first.tail)
        val later = engine.frame("hello world, more", 500, reducedMotion = false, dtMs = 1_000)
        assertFalse(later.tail.contains("more") || later.markdown.contains("more"))
        val caughtUp = engine.frame("hello world, more", 1_000, reducedMotion = true, dtMs = 16)
        assertEquals("hello world, more", caughtUp.markdown)
        assertEquals("", caughtUp.tail)
        assertFalse(engine.finish("done").cursor)
    }
}
