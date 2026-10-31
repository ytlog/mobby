package com.github.ytlog.mobby.android.programcenter

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.github.ytlog.mobby.android.MobbyApplication
import com.github.ytlog.mobby.android.conversation.ui.MobbyDarkScheme
import com.github.ytlog.mobby.android.conversation.ui.MobbyLightScheme
import com.github.ytlog.mobby.android.conversation.ui.TextButton
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.ProgramView
import com.github.ytlog.mobby.android.programs.event.EntryCapability
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * mobby in the system share sheet, for text only. The user picks which program receives it; the sending app's
 * identity is not passed on, since an intent's claims about its sender cannot be trusted. Enabled only while some
 * program takes shares (see [syncShareEntry]).
 */
class ProgramShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val platform = (application as MobbyApplication).programs
        setContent {
            var targets by remember { mutableStateOf<List<ProgramView>?>(null) }
            var sending by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { targets = shareTargets(platform.programs.first()) }
            val problem = when {
                text.isNullOrBlank() -> AppStrings.shareNeedsText
                !EntryCapability.fits(text, subject) -> AppStrings.sharedTextTooLong
                targets?.isEmpty() == true -> AppStrings.noProgramTakesShares
                else -> null
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MobbyDarkScheme else MobbyLightScheme) {
                AlertDialog(onDismissRequest = ::finish, title = { Text(AppStrings.shareToProgram) },
                    text = {
                        if (problem != null) Text(problem)
                        else Column { targets.orEmpty().forEach { program ->
                            TextButton(onClick = {
                                if (sending) return@TextButton
                                sending = true
                                lifecycleScope.launch {
                                    val delivered = platform.share(program.id, text!!, subject)
                                    Toast.makeText(applicationContext, if (delivered) AppStrings.sharedTo(program.name) else AppStrings.shareRefused(program.name),
                                        Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }) { Text(program.name) }
                        } }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = ::finish) { Text(AppStrings.cancel) } })
            }
        }
    }
}

/** Keeps mobby out of the share sheet while no program could receive what is shared. */
internal fun syncShareEntry(context: Context, programs: List<ProgramView>) {
    val state = if (shareTargets(programs).isEmpty()) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    context.packageManager.setComponentEnabledSetting(ComponentName(context, ProgramShareActivity::class.java), state, PackageManager.DONT_KILL_APP)
}
