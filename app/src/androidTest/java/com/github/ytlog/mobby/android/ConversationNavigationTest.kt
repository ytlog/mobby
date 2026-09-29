package com.github.ytlog.mobby.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated empty conversations, never submits a model request or changes gateway settings. */
@RunWith(AndroidJUnit4::class)
class ConversationNavigationTest {
    @Test fun recreatingAfterUserSwitchDoesNotReplayOldConversationLink() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val actions = (context.applicationContext as MobbyApplication).conversations
        val original = withTimeout(10_000) { actions.state.first { it.selected != null } }.selected!!.conversation
        val linked = actions.create(original.config)
        val chosen = actions.create(original.config)
        suspend fun selected(id: ConversationId) = withTimeout(10_000) {
            actions.state.first { it.selected?.conversation?.id == id }
        }
        try {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).putExtra("conversationId", linked.value)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                selected(linked)
                actions.select(chosen)
                selected(chosen)
                scenario.recreate()
                instrumentation.waitForIdleSync()
                delay(1_000) // Allow the Room-backed navigation launched by onCreate to settle.
                assertEquals(chosen, actions.state.first().selected?.conversation?.id)
                // An actually new incoming link must still be honored after recreation.
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .setAction(Intent.ACTION_VIEW).putExtra("conversationId", linked.value))
                }
                selected(linked)
            }
        } finally {
            actions.delete(linked, true)
            actions.delete(chosen, true)
            actions.select(original.id)
            selected(original.id)
        }
    }
}
