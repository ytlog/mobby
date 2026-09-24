package com.github.ytlog.mobby.android.runtime.android

import android.app.Application
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import com.github.ytlog.mobby.android.runtime.api.RunId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RuntimeTaskNotificationTest {
    @Test fun `blocked task notification channel cannot serve as stop entry`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("runtime", "Task", android.app.NotificationManager.IMPORTANCE_NONE))
        assertFalse(RuntimeTaskNotification.canShow(context))
    }

    internal class NotificationService : RuntimeService() {
        val received = java.util.concurrent.LinkedBlockingQueue<RunId>()
        override suspend fun stopFromNotification(runId: RunId) { received.offer(runId) }
        override suspend fun stopForHost(cause: com.github.ytlog.mobby.android.runtime.engine.StopCause) = Unit
    }

    @Test fun `notification action reaches runtime cancellation with original run id`() {
        val context = RuntimeEnvironment.getApplication()
        val notification = RuntimeTaskNotification.build(context, "running", RunId("fixture-run"), null)
        val intent = shadowOf(notification.actions.single().actionIntent).savedIntent
        val controller = org.robolectric.Robolectric.buildService(NotificationService::class.java)
        val service = controller.get()
        try {
            service.onStartCommand(intent, 0, 1)
            assertEquals(RunId("fixture-run"), service.received.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { controller.destroy() }
    }

    @Test fun `stop button remains bound to the displayed run when a new notification replaces it`() {
        val context = RuntimeEnvironment.getApplication()
        val old = RuntimeTaskNotification.build(context, "running", RunId("old"), null)
        val next = RuntimeTaskNotification.build(context, "running", RunId("next"), null)
        val oldIntent = shadowOf(old.actions.single().actionIntent).savedIntent
        val nextIntent = shadowOf(next.actions.single().actionIntent).savedIntent
        assertEquals(RunId("old"), RuntimeTaskNotification.stopTarget(oldIntent))
        assertEquals(RunId("next"), RuntimeTaskNotification.stopTarget(nextIntent))
        assertNotEquals(old.actions.single().actionIntent, next.actions.single().actionIntent)
        assertNull(RuntimeTaskNotification.stopTarget(Intent()))
        assertNull(RuntimeTaskNotification.build(context, "starting", null, null).actions)
    }
}
