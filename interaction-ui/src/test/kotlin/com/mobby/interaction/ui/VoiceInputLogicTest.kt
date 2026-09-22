package com.mobby.interaction.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputLogicTest {
    @Test fun `rms maps silence near zero and loud speech near full`() {
        assertEquals(0f, rmsToLevel(-2f), 0.001f)
        assertEquals(0f, rmsToLevel(-8f), 0.001f)
        assertEquals(1f, rmsToLevel(10f), 0.001f)
        assertEquals(1f, rmsToLevel(24f), 0.001f)
        assertTrue(rmsToLevel(4f) in 0.4f..0.6f)
        assertEquals(0f, rmsToLevel(Float.NaN), 0.001f)
    }

    @Test fun `louder input raises the spectrum and time only changes its shape`() {
        val quiet = spectrumBars(0.05f, 1.2f)
        val loud = spectrumBars(0.9f, 1.2f)
        assertEquals(VoiceSpectrumBars, quiet.size)
        assertTrue(quiet.all { it in 0f..1f })
        assertTrue(loud.all { it in 0f..1f })
        assertTrue(loud.average() > quiet.average() + 0.08)
        assertFalse(loud.contentEquals(spectrumBars(0.9f, 3.4f)))
        val moving = spectrumBars(0.6f, 2f)
        assertTrue(moving.maxOrNull()!! - moving.minOrNull()!! > 0.5f)
        val center = moving.slice(moving.size / 2 - 12 until moving.size / 2 + 12)
        assertTrue(center.maxOrNull()!! - center.minOrNull()!! > 0.7f)
        val middle = loud.slice(loud.size / 2 - 8 until loud.size / 2 + 8).average()
        val sides = (loud.take(8) + loud.takeLast(8)).average()
        assertTrue(middle > sides * 2.5)
    }

    @Test fun `model transfer reports percent size and a full bar while loading`() {
        val idle = VoiceModelTransfer(0, 0)
        assertNull(idle.fraction)
        assertEquals("正在准备语音模型", idle.label)
        assertNull(idle.percent)
        val quarter = VoiceModelTransfer(10L * 1024 * 1024, 40L * 1024 * 1024)
        assertEquals(0.25f, quarter.fraction!!, 0.001f)
        assertEquals("25%", quarter.percent)
        assertEquals("正在下载语音模型", quarter.label)
        assertEquals("10.0 MB / 40.0 MB", quarter.size)
        val loading = VoiceModelTransfer(quarter.read, quarter.total, loading = true)
        assertEquals(1f, loading.fraction!!, 0.001f)
        assertEquals("100%", loading.percent)
        assertEquals("正在加载语音模型", loading.label)
        assertEquals("0.5 MB", voiceSizeLabel(512 * 1024))
    }
}
