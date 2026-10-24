package com.github.ytlog.mobby.android.graph

import android.app.PendingIntent
import android.content.Context
import com.github.ytlog.mobby.android.programcenter.ProgramUiActivity
import com.github.ytlog.mobby.android.programs.ProgramPlatform
import com.github.ytlog.mobby.android.runtime.android.TermuxProgramLauncher
import kotlinx.coroutines.launch
import java.io.File
import com.github.ytlog.mobby.android.conversation.data.ConversationFactory
import com.github.ytlog.mobby.android.runtime.android.RuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One application-owned runtime and projection shared by the main and floating interfaces. */
class AppGraph(context: Context, notification: () -> PendingIntent) {
    private val conversationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val runtime = RuntimeHost(context.applicationContext, notification)
    val conversations = ConversationFactory.create(context.applicationContext, runtime.client, runtime.admin, runtime.diagnostics, conversationScope)
    private val programScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Programs are written into the default workspace by the phone AI and installed from there.
    val programs = ProgramPlatform.create(context.applicationContext,
        File(context.applicationContext.filesDir, "libtermux/home/workspace/programs"),
        { logDir -> TermuxProgramLauncher(context.applicationContext, programScope, logDir) },
        { programId -> ProgramUiActivity.pendingIntent(context.applicationContext, programId) },
    ).also { platform -> programScope.launch { platform.start() } }
}
