package com.github.ytlog.mobby.android.runtime.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.RunId

internal object RuntimeTaskNotification {
    private const val CHANNEL = "runtime"
    const val STOP = "com.github.ytlog.mobby.android.STOP_RUNTIME_TASK"
    const val RUN_ID = "runId"

    fun createChannel(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, AppStrings.taskExecution, android.app.NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun canShow(context: Context): Boolean {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    fun build(context: Context, text: String, runId: RunId?, open: PendingIntent?): Notification {
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(AppStrings.mobbyIsRunningATask).setContentText(text)
            .setContentIntent(open).setOngoing(true)
        if (runId != null) {
            val intent = Intent(context, RuntimeService::class.java).setAction(STOP)
                .setData(Uri.Builder().scheme("mobby").authority("stop-task").appendPath(runId.value).build())
                .putExtra(RUN_ID, runId.value)
            val stop = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, AppStrings.stop, stop).build())
        }
        return builder.build()
    }

    fun stopTarget(intent: Intent?): RunId? = intent?.takeIf { it.action == STOP }
        ?.getStringExtra(RUN_ID)?.takeIf { it.isNotBlank() }?.let(::RunId)
}
