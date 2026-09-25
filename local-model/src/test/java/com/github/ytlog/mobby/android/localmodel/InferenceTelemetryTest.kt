package com.github.ytlog.mobby.android.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InferenceTelemetryTest {
    @Test fun `records cache reuse and first output without request content`() {
        var now = 0L
        val metrics = InferenceTelemetry { now }
        val run = metrics.begin("model-id", "tools")
        now += 5_000_000
        run.prompt(1200, 900)
        now += 30_000_000
        assertEquals(35L, metrics.snapshot()?.elapsedMs)
        run.prefillComplete()
        now += 10_000_000
        run.token(1)
        now += 20_000_000
        run.text()
        now += 5_000_000
        run.finish(true)
        val result = metrics.snapshot()!!
        assertEquals("generated", result.stage)
        assertEquals(1200, result.promptTokens)
        assertEquals(900, result.reusedTokens)
        assertEquals(5L, result.setupMs)
        assertEquals(30L, result.prefillMs)
        assertEquals(45L, result.firstTokenMs)
        assertEquals(65L, result.firstTextMs)
        assertEquals(70L, result.elapsedMs)
        assertNull(result.json()["prompt"])
        assertNull(result.json()["content"])
    }

    @Test fun `failed request does not leave a running metric`() {
        var now = 0L
        val metrics = InferenceTelemetry { now }
        val run = metrics.begin("model-id", "text")
        now += 2_000_000
        run.finish(false)
        now += 8_000_000
        assertEquals("failed", metrics.snapshot()?.stage)
        assertEquals(2L, metrics.snapshot()?.elapsedMs)
    }
}
