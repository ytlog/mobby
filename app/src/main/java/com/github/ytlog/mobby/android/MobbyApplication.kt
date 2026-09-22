package com.github.ytlog.mobby.android

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.github.ytlog.mobby.android.runtime.android.RuntimeHost
import com.github.ytlog.mobby.android.interaction.data.InteractionFactory
import com.github.ytlog.mobby.android.interaction.domain.InteractionUseCases
import kotlinx.coroutines.*

class MobbyApplication : Application() {
    lateinit var runtime: RuntimeHost
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var interaction: InteractionUseCases
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeHost(this) {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
        interaction = InteractionFactory.create(this, runtime.client, runtime.admin, runtime.diagnostics, applicationScope)
    }
}
