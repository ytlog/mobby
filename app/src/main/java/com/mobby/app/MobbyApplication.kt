package com.mobby.app

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.mobby.runtime.android.RuntimeHost

class MobbyApplication : Application() {
    lateinit var runtime: RuntimeHost
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeHost(this) {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
