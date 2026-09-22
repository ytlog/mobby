package com.mobby.interaction.ui

import org.junit.Assert.*
import org.junit.Test

class StreamEngineTest {
    @Test fun `arrived text stays visible and markdown waits for the parse interval`() {
        val engine = StreamEngine(parseIntervalMs = 1_000)
        val first = engine.frame("hello world", 0, reducedMotion = false)
        assertTrue(first.cursor)
        assertEquals("hello world", first.markdown)
        assertEquals("", first.tail)
        val later = engine.frame("hello world, more", 500, reducedMotion = false)
        assertEquals("hello world", later.markdown)
        assertEquals(", more", later.tail)
        val caughtUp = engine.frame("hello world, more", 1_000, reducedMotion = false)
        assertEquals("hello world, more", caughtUp.markdown)
        assertEquals("", caughtUp.tail)
        assertFalse(engine.finish("done").cursor)
    }
}
