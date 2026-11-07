package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.domain.ConversationState
import com.github.ytlog.mobby.android.conversation.domain.ExecutionPhase

internal data class TaskResult(val conversation: ConversationId, val title: String, val phase: ExecutionPhase)

/** Runs seen unfinished in [previous] that have ended in [current]; runs already over when first seen are not news. */
internal fun finishedRuns(previous: ConversationState, current: ConversationState): List<TaskResult> {
    val running = previous.conversations.filter { it.run != null && !petTerminal(it.phase) }.associate { it.conversation.id to it.run }
    return current.conversations.mapNotNull { row ->
        val phase = row.phase ?: return@mapNotNull null
        if (!petTerminal(phase) || row.run == null || running[row.conversation.id] != row.run) return@mapNotNull null
        TaskResult(row.conversation.id, row.conversation.title.ifBlank { AppStrings.currentTask }, phase)
    }
}

/**
 * Tells the user a task ended while mobby was in the background; tapping opens its conversation through [open], which the
 * app supplies so this layer does not name its activity.
 */
internal class TaskResultNotifier(private val context: Context, private val open: (ConversationId) -> Intent) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, AppStrings.taskResults, NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(result: TaskResult) {
        val open = open(result.conversation)
        val id = result.conversation.value.hashCode()
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(runtimePaths(context).format(result.title)).setContentText(petStatus(result.phase)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        manager.notify(TAG, id, notification)
    }

    fun clear(conversation: ConversationId) = manager.cancel(TAG, conversation.value.hashCode())

    private companion object {
        const val CHANNEL = "task-results"
        const val TAG = "task-result"
    }
}
