package com.github.ytlog.mobby.android.programcenter

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import com.github.ytlog.mobby.android.MainActivity
import com.github.ytlog.mobby.android.conversation.ui.ProgramShortcut
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramLauncherShortcutsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun program(id: String, state: UserState = UserState.ENABLED, availability: ProgramAvailability = ProgramAvailability.WORKING, ui: Boolean = true) =
        ProgramView(id, id.uppercase(), "1.0.0", state, availability, true, ui, emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun `a launch is decided from the program's current state`() {
        val programs = listOf(
            program("page"),
            program("config", availability = ProgramAvailability.NEEDS_CONFIG),
            program("paused", UserState.PAUSED, ProgramAvailability.PAUSED),
            program("logic", ui = false),
        )
        assertEquals(ProgramLaunch.Page("page"), programLaunch("page", programs))
        assertEquals(ProgramLaunch.Details("config"), programLaunch("config", programs))
        assertEquals(ProgramLaunch.Details("paused"), programLaunch("paused", programs))
        assertEquals(ProgramLaunch.Details("logic"), programLaunch("logic", programs))
        assertEquals(ProgramLaunch.List, programLaunch("deleted", programs))
    }

    @Test fun `the launcher keeps at most four programs and never more than the system allows`() {
        val programs = (1..8).map { program("p$it") }
        assertEquals(4, launcherShortcuts(programs, emptyMap(), systemLimit = 15).size)
        assertEquals(listOf("p3", "p1"), launcherShortcuts(programs, mapOf("p3" to 2L, "p1" to 1L), systemLimit = 2).map { it.id })
        assertEquals(emptyList<ProgramShortcut>(), launcherShortcuts(listOf(program("x", UserState.PAUSED, ProgramAvailability.PAUSED)), emptyMap(), 15))
    }

    @Test fun `a shortcut opens mobby with only the program id, so the target is chosen at launch`() {
        val intent = programShortcutIntent(context, "header-watch")
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("header-watch", requestedProgram(intent))
        assertNull(requestedProgram(Intent(context, MainActivity::class.java).putExtra("programId", "header-watch")))
        assertNull(requestedProgram(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test fun `syncing replaces the dynamic shortcuts, so deleted or paused programs disappear`() {
        val shortcuts = ProgramLauncherShortcuts(context)
        shortcuts.sync(listOf(ProgramShortcut("a", "A", opensPage = true), ProgramShortcut("b", "B", opensPage = false)))
        assertEquals(listOf("program:a", "program:b"), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
        shortcuts.sync(listOf(ProgramShortcut("b", "B", opensPage = false)))
        val remaining = ShortcutManagerCompat.getDynamicShortcuts(context)
        assertEquals(listOf("program:b"), remaining.map { it.id })
        assertEquals("B", remaining.single().shortLabel)
        assertEquals("b", requestedProgram(remaining.single().intent))
    }
}
