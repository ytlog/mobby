package com.github.ytlog.mobby.android.interaction.ui

import org.junit.Assert.*
import org.junit.Test

class StreamEngineTest {
    @Test fun `stream updates remain a single markdown snapshot without a plain text tail`() {
        val engine = StreamEngine(parseIntervalMs = 1_000)
        val first = engine.frame("hello world", 0, reducedMotion = false)
        assertEquals("hello world", first.markdown)
        val later = engine.frame("hello world, more", 500, reducedMotion = false)
        assertEquals("hello world", later.markdown)
        val caughtUp = engine.frame("hello world, more", 1_000, reducedMotion = false)
        assertEquals("hello world, more", caughtUp.markdown)
        assertEquals("done", engine.finish("done").markdown)
    }
}
