package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.AgentOption
import com.github.ytlog.mobby.android.conversation.domain.Conversation
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.domain.NextTurnConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentAvailabilityTest {
    private val conversation = Conversation(ConversationId("draft"), NextTurnConfig(AgentId.CODEX, "model", null, "default", "missing-gateway"))
    private val options = listOf(AgentOption(AgentId.CODEX, emptyMap(), "网关未配置", true, emptySet(), resources = true, images = true))

    @Test fun `photo camera and file remain available for a draft before gateway setup`() {
        val actions = attachmentAvailability(conversation, options, runtimeReady = true, cameraBusy = false)
        assertEquals(AttachmentAvailability(photos = true, files = true), actions)
    }

    @Test fun `a missing conversation cannot launch an attachment picker`() {
        assertEquals(AttachmentAvailability(false, false), attachmentAvailability(null, options, runtimeReady = true, cameraBusy = false))
    }
}
