package com.github.ytlog.mobby.android.graph

import android.app.PendingIntent
import android.content.Context
import com.github.ytlog.mobby.android.interaction.data.InteractionFactory
import com.github.ytlog.mobby.android.runtime.android.RuntimeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One application-owned runtime and projection shared by the main and floating interfaces. */
class AppGraph(context: Context, notification: () -> PendingIntent) {
    private val interactionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val runtime = RuntimeHost(context.applicationContext, notification)
    val interaction = InteractionFactory.create(context.applicationContext, runtime.client, runtime.admin, runtime.diagnostics, interactionScope)
}
