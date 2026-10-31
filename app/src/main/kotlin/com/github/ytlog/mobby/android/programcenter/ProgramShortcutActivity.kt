package com.github.ytlog.mobby.android.programcenter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.github.ytlog.mobby.android.MobbyApplication
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Where program shortcuts land. It is not exported, so only mobby's own shortcuts reach it and a tap here is the user's.
 * It runs in a task of its own: a program that takes the tap runs without mobby coming to the front.
 */
class ProgramShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requestedProgram(intent)
        if (savedInstanceState != null || id == null) return finish()
        val platform = (application as MobbyApplication).programs
        lifecycleScope.launch {
            when (val launch = programLaunch(id, platform.programs.first())) {
                is ProgramLaunch.Run ->
                    // The program can stop listening between the lookup and the tap; its details then say why.
                    if (platform.shortcut(id)) Toast.makeText(applicationContext, AppStrings.shortcutDelivered(launch.name), Toast.LENGTH_SHORT).show()
                    else open(programDetailsIntent(this@ProgramShortcutActivity, id))
                is ProgramLaunch.Page -> open(ProgramUiActivity.intent(this@ProgramShortcutActivity, id))
                is ProgramLaunch.Details, ProgramLaunch.List -> open(programDetailsIntent(this@ProgramShortcutActivity, id))
            }
            finish()
        }
    }

    private fun open(intent: Intent) = startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
