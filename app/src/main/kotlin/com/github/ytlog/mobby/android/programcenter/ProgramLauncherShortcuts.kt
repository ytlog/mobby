package com.github.ytlog.mobby.android.programcenter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.IconCompat
import com.github.ytlog.mobby.android.MainActivity
import com.github.ytlog.mobby.android.conversation.ui.MobbyColors
import com.github.ytlog.mobby.android.conversation.ui.ProgramShortcut
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.event.EntryCapability
import com.github.ytlog.mobby.android.programs.program.ProgramAvailability
import com.github.ytlog.mobby.android.programs.program.UserState

private const val ACTION_OPEN_PROGRAM = "com.github.ytlog.mobby.android.action.OPEN_PROGRAM"
private const val EXTRA_PROGRAM = "programId"
private const val LAUNCHER_LIMIT = 4

/** Where a launcher shortcut leads, decided when it is tapped because the program may have changed since it was made. */
internal sealed interface ProgramLaunch {
    data class Page(val id: String) : ProgramLaunch
    /** The program listens for its shortcut: the tap goes to its background logic and nothing opens. */
    data class Run(val id: String, val name: String) : ProgramLaunch
    data class Details(val id: String) : ProgramLaunch
    data object List : ProgramLaunch
}

internal fun programLaunch(id: String, programs: List<ProgramView>): ProgramLaunch {
    val program = programs.firstOrNull { it.id == id } ?: return ProgramLaunch.List
    if (program.subscriptions.any { it.event == EntryCapability.SHORTCUT && it.active }) return ProgramLaunch.Run(id, program.name)
    val opens = program.userState == UserState.ENABLED && program.availability == ProgramAvailability.WORKING && program.hasUi
    return if (opens) ProgramLaunch.Page(id) else ProgramLaunch.Details(id)
}

/** The long-press menu shows the same programs as the drawer, in the same order, within the launcher's limit. */
internal fun launcherShortcuts(programs: List<ProgramView>, opened: Map<String, Long>, systemLimit: Int): List<ProgramShortcut> =
    programShortcuts(programs, opened, minOf(LAUNCHER_LIMIT, systemLimit))

/** Programs offered text shared from another app: those whose `entry.share` subscription can receive it now. */
internal fun shareTargets(programs: List<ProgramView>): List<ProgramView> = programs.filter { program ->
    program.availability == ProgramAvailability.WORKING && program.subscriptions.any { it.event == EntryCapability.SHARE && it.active }
}

/**
 * Shortcuts go to [ProgramShortcutActivity] with only the program id; [programLaunch] then decides. That activity is not
 * exported, so no other app can send a program a shortcut tap.
 */
internal fun programShortcutIntent(context: Context, programId: String): Intent = programIntent(context, programId, ProgramShortcutActivity::class.java)

/** Opens the program's details or the program list in mobby; also what shortcuts made before [ProgramShortcutActivity] still send. */
internal fun programDetailsIntent(context: Context, programId: String): Intent = programIntent(context, programId, MainActivity::class.java)

private fun programIntent(context: Context, programId: String, target: Class<*>) =
    Intent(ACTION_OPEN_PROGRAM, Uri.parse("mobby-program://$programId"), context, target).putExtra(EXTRA_PROGRAM, programId)

/** Reopening mobby from Recents replays the launching intent; that is not a new request to open the program. */
internal fun requestedProgram(intent: Intent?): String? = intent
    ?.takeIf { it.action == ACTION_OPEN_PROGRAM && it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }
    ?.getStringExtra(EXTRA_PROGRAM)

/** Launcher shortcuts for programs: the dynamic ones under mobby's icon, and ones the user pins to the home screen. */
internal class ProgramLauncherShortcuts(context: Context) {
    private val context = context.applicationContext

    val systemLimit: Int get() = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

    /** [names] are all installed programs, so shortcuts already pinned to the home screen get the current name and target. */
    fun sync(shortcuts: List<ProgramShortcut>, names: Map<String, String> = emptyMap()) {
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts.map { info(it.id, it.name) })
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .mapNotNull { shortcut -> shortcut.id.removePrefix("program:").takeIf { shortcut.id.startsWith("program:") }?.let { id -> names[id]?.let { info(id, it) } } }
        if (pinned.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, pinned)
    }

    fun pinSupported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin the program; the launcher shows its own confirmation, and the result is not reported back. */
    fun requestPin(program: ProgramView): Boolean = ShortcutManagerCompat.requestPinShortcut(context, info(program.id, program.name), null)

    private fun info(id: String, name: String) = ShortcutInfoCompat.Builder(context, "program:$id")
        .setShortLabel(name)
        .setIcon(IconCompat.createWithAdaptiveBitmap(initialIcon(name)))
        .setIntent(programShortcutIntent(context, id))
        .build()
}

/** The same initial the drawer and the empty page show, drawn as an adaptive icon (108dp canvas, 72dp safe zone). */
private fun initialIcon(name: String): Bitmap {
    val size = 216
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(MobbyColors.Light.menuAccent.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size * 0.32f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    val initial = name.trim().take(1).uppercase().ifEmpty { "?" }
    canvas.drawText(initial, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    return bitmap
}
