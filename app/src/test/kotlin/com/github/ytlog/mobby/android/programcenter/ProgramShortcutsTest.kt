package com.github.ytlog.mobby.android.programcenter

import com.github.ytlog.mobby.android.conversation.ui.ProgramShortcut
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.UserState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramShortcutsTest {
    private fun program(id: String, state: UserState = UserState.ENABLED, availability: ProgramAvailability = ProgramAvailability.WORKING, ui: Boolean = true) =
        ProgramView(id, id.uppercase(), "1.0.0", state, availability, true, ui, emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `recently opened programs come first and paused ones are left out`() {
        val programs = listOf(program("b"), program("a"), program("paused", UserState.PAUSED, ProgramAvailability.PAUSED), program("c"))
        assertEquals(listOf("c", "a", "b"), programShortcuts(programs, opened = mapOf("c" to 20L, "a" to 10L)).map { it.id })
        assertEquals(5, programShortcuts((1..8).map { program("p$it") }, emptyMap()).size)
    }

    @Test fun `only a working program with a page opens directly, others go to details with their state`() {
        val shortcuts = programShortcuts(listOf(program("page"), program("config", availability = ProgramAvailability.NEEDS_CONFIG), program("logic", ui = false)), emptyMap())
            .associateBy { it.id }
        assertEquals(ProgramShortcut("page", "PAGE", opensPage = true), shortcuts["page"])
        assertEquals(ProgramShortcut("config", "CONFIG", opensPage = false, note = availabilityLabel(ProgramAvailability.NEEDS_CONFIG)), shortcuts["config"])
        assertEquals(ProgramShortcut("logic", "LOGIC", opensPage = false), shortcuts["logic"])
    }
}
