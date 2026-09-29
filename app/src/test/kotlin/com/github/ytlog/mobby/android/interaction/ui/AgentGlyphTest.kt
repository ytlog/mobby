package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.AgentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentGlyphTest {
    @Test fun `each agent uses a distinct filled mark inside the icon box`() {
        val marks = AgentId.values().map { it.glyph() }
        assertEquals(marks.size, marks.map { it.path.getBounds() }.toSet().size)
        marks.forEach { mark ->
            assertTrue(mark.filled)
            assertFalse(mark.path.isEmpty)
            val bounds = mark.path.getBounds()
            assertTrue(bounds.width > 10f && bounds.height > 10f)
            assertTrue(bounds.left > -2f && bounds.top > -2f)
            assertTrue(bounds.right < 26f && bounds.bottom < 26f)
        }
        assertTrue(AgentId.OPEN_CODE.glyph().evenOdd)
        assertFalse(AgentId.CODEX.glyph().evenOdd)
        assertFalse(AgentId.CLAUDE_CODE.glyph().evenOdd)
    }
}
