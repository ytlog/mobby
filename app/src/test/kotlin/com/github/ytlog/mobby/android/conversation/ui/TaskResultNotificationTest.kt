package com.github.ytlog.mobby.android.conversation.ui

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.github.ytlog.mobby.android.conversation.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskResultNotificationTest {
    private val config = NextTurnConfig(AgentId.PI, "model", null, "default", "PI")
    /** As the store reports it: the occupying execution is only set while the run holds the conversation. */
    private fun row(id: String, phase: ExecutionPhase?, run: String? = "run-$id", title: String = "整理账单"): ConversationSummary {
        val busy = phase != null && !petTerminal(phase)
        return ConversationSummary(Conversation(ConversationId(id), config, title = title), phase, occupied = busy,
            execution = run?.takeIf { busy }?.let(::ExecutionId), run = run?.let(::ExecutionId))
    }
    private fun state(vararg rows: ConversationSummary) = ConversationState(rows.toList(), loading = false)

    @Test fun `a run that ends after being seen running is reported once`() {
        val running = state(row("a", ExecutionPhase.RUNNING))
        val done = state(row("a", ExecutionPhase.SUCCEEDED))
        assertEquals(listOf(TaskResult(ConversationId("a"), "整理账单", ExecutionPhase.SUCCEEDED)), finishedRuns(running, done))
        assertEquals(emptyList<TaskResult>(), finishedRuns(done, done))
    }

    @Test fun `failures and stops are reported as they are`() {
        for (phase in listOf(ExecutionPhase.FAILED, ExecutionPhase.CANCELLED, ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN))
            assertEquals(phase, finishedRuns(state(row("a", ExecutionPhase.RUNNING)), state(row("a", phase))).single().phase)
    }

    @Test fun `runs already finished when the app starts or never seen running are not reported`() {
        assertEquals(emptyList<TaskResult>(), finishedRuns(ConversationState(), state(row("a", ExecutionPhase.SUCCEEDED))))
        val earlier = state(row("a", ExecutionPhase.RUNNING, run = "old"))
        assertEquals(emptyList<TaskResult>(), finishedRuns(earlier, state(row("a", ExecutionPhase.SUCCEEDED, run = "new"))))
    }

    @Test fun `the notification names the conversation and opens it`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val notifier = TaskResultNotifier(context)
        notifier.show(TaskResult(ConversationId("a"), "整理账单", ExecutionPhase.FAILED))
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("整理账单", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(UiStrings.failed, posted.extras.getString(android.app.Notification.EXTRA_TEXT))
        val opened: Intent = shadowOf(posted.contentIntent).savedIntent
        assertEquals("a", opened.getStringExtra("conversationId"))
    }
}
