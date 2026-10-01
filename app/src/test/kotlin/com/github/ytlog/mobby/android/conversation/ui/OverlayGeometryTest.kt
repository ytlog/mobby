package com.github.ytlog.mobby.android.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    @Test fun floatingConversationFitsAfterDisplayShrinks() {
        val frame = floatingConversationFrame(400, 600, 1f)
        assertEquals(376, frame.width)
        assertEquals(504, frame.height)
        assertTrue(frame.x >= 0 && frame.x + frame.width <= 400)
        assertTrue(frame.y >= 0 && frame.y + frame.height <= 600)
    }

    @Test fun floatingConversationFitsVerySmallDisplay() {
        val frame = floatingConversationFrame(80, 90, 1f)
        assertTrue(frame.width in 1..80)
        assertTrue(frame.height in 1..90)
        assertTrue(frame.x >= 0 && frame.x + frame.width <= 80)
        assertTrue(frame.y >= 0 && frame.y + frame.height <= 90)
    }

    @Test fun floatingConversationAvoidsVerticalHinge() {
        val frame = floatingConversationFrame(800, 900, 1f, OverlayFold(390, 0, 410, 900, vertical = true))
        assertTrue(frame.x + frame.width <= 390 || frame.x >= 410)
        assertTrue(frame.width > 300)
    }

    @Test fun bubbleCannotRemainOnHinge() {
        val (frame, collapsed) = petFrameOutsideFold(
            PetFrame(380, 200, 56, 56, true), 800, 900, 56,
            OverlayFold(390, 0, 410, 900, vertical = true),
        )
        assertTrue(frame.x + frame.width <= 390 || frame.x >= 410)
        assertTrue(!collapsed)
    }

    @Test fun bubbleMovesToSideThatCanFitIt() {
        val (frame, collapsed) = petFrameOutsideFold(
            PetFrame(0, 200, 56, 56, true), 300, 600, 56,
            OverlayFold(32, 0, 48, 600, vertical = true),
        )
        assertTrue(frame.x >= 48)
        assertEquals(56, frame.width)
        assertTrue(!collapsed)
    }

    @Test fun expandedTrayCollapsesWhenNeitherSideCanFitIt() {
        val (frame, collapsed) = petFrameOutsideFold(
            PetFrame(80, 200, 308, 100, true), 500, 800, 56,
            OverlayFold(240, 0, 260, 800, vertical = true),
        )
        assertTrue(collapsed)
        assertEquals(56, frame.width)
        assertTrue(frame.x + frame.width <= 240 || frame.x >= 260)
    }
}
