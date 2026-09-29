package com.github.ytlog.mobby.android.interaction.ui

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FrostRegionTest {
    @Test fun `frost region blurs a contrast edge instead of copying it sharp`() {
        val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        for (x in 20 until 40) for (y in 0 until 40) source.setPixel(x, y, Color.WHITE)
        val frosted = frostRegion(source, 0, 0, 40, 40)
        assertEquals(40, frosted.width)
        assertEquals(40, frosted.height)
        val edge = Color.red(frosted.getPixel(20, 20))
        val innerWhite = Color.red(frosted.getPixel(36, 20))
        val innerBlack = Color.red(frosted.getPixel(4, 20))
        assertTrue("expected a smeared edge, got $edge", edge in 1..254)
        assertTrue("frost should wash the white field, got $innerWhite", innerWhite < 250)
        assertTrue("frost should wash the black field, got $innerBlack", innerBlack > 5)
    }
}
