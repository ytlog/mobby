package com.github.ytlog.mobby.android.programcenter

import android.content.Context
import android.content.SharedPreferences
import com.github.ytlog.mobby.android.conversation.ui.ProgramShortcut
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.UserState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Drawer shortcuts: enabled programs, most recently opened first; only a working program with a page opens directly. */
internal fun programShortcuts(programs: List<ProgramView>, opened: Map<String, Long>, limit: Int = 5): List<ProgramShortcut> =
    programs.filter { it.userState == UserState.ENABLED }
        .sortedWith(compareByDescending<ProgramView> { opened[it.id] ?: 0L }.thenBy { it.name })
        .take(limit)
        .map { program ->
            val working = program.availability == ProgramAvailability.WORKING
            ProgramShortcut(program.id, program.name, opensPage = working && program.hasUi,
                note = if (working) null else availabilityLabel(program.availability))
        }

/** When each program page was last opened, kept on this device only to order the drawer. */
internal class ProgramOpenHistory(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("program-opened", Context.MODE_PRIVATE)

    fun record(programId: String, at: Long = System.currentTimeMillis()) = prefs.edit().putLong(programId, at).apply()

    fun changes(): Flow<Map<String, Long>> = callbackFlow {
        fun current() = prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        trySend(current())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
