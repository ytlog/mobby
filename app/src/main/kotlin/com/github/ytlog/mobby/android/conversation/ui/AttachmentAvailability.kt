package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.AgentOption
import com.github.ytlog.mobby.android.conversation.domain.Conversation

internal data class AttachmentAvailability(val photos: Boolean, val files: Boolean)

internal fun attachmentAvailability(target: Conversation?, agents: List<AgentOption>, runtimeReady: Boolean, cameraBusy: Boolean): AttachmentAvailability {
    val canImport = target != null && !target.archived && !target.deleted && target.draft.pendingAttachment == null && target.draft.attachments.size < 4
    val agent = agents.firstOrNull { it.agent == target?.config?.agent }
    return AttachmentAvailability(
        photos = canImport && runtimeReady && !cameraBusy && agent?.images == true,
        files = canImport && runtimeReady && agent?.resources == true,
    )
}
