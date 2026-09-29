package com.github.ytlog.mobby.android.interaction.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.interaction.data.*
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.localization.AppStrings

/** Owns draft text, attachments and selected capabilities. */
internal class DraftManager(private val db: InteractionDatabase) {
    private val dao = db.dao()
    suspend fun beginAttachment(id: ConversationId, pending: PendingAttachment) {
        mutate(id) { c ->
            require(!c.archived && !c.deleted && c.config.workspace == pending.workspace && c.draft.attachments.size < 4)
            require(c.draft.pendingAttachment?.let { it.error != null } != false)
            c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, pendingAttachment = pending))
        }
    }
    suspend fun finishAttachment(id: ConversationId, pendingId: String, result: DataResult<Attachment>) {
        mutate(id) { c ->
            val pending = c.draft.pendingAttachment
            if (pending?.id != pendingId) c else when (result) {
                is DataResult.Failed -> c.copy(draft = c.draft.copy(pendingAttachment = pending.copy(error = result.message)))
                is DataResult.Loaded -> {
                    if (c.archived || c.deleted || c.config.workspace != pending.workspace || (c.draft.attachments + result.value.ref).distinct().size > 4)
                        c.copy(draft = c.draft.copy(pendingAttachment = pending.copy(error = AppStrings.conversationOrAttachmentChangedRestoreTheConversationAndRetry)))
                    else c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, attachments = (c.draft.attachments + result.value.ref).distinct(), pendingAttachment = null))
                }
            }
        }
    }
    suspend fun discardAttachment(id: ConversationId, pendingId: String) {
        mutate(id) { c ->
            if (c.draft.pendingAttachment?.id == pendingId) c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, pendingAttachment = null)) else c
        }
    }
    suspend fun restoreDraft(id: ConversationId, text: String, attachments: List<String>) = mutate(id) { c ->
        require(!c.archived && !c.deleted && attachments.size <= 4)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, text = text, selectionStart = text.length, selectionEnd = text.length, attachments = attachments))
    }
    suspend fun setAttachment(id: ConversationId, ref: String, enabled: Boolean) = mutate(id) { c ->
        require(!c.archived && !c.deleted)
        val refs = if (enabled) (c.draft.attachments + ref).distinct() else c.draft.attachments - ref
        require(refs.size <= 4)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, attachments = refs))
    }
    suspend fun setSkill(id: ConversationId, ref: String, enabled: Boolean) = mutate(id) { c ->
        require(!enabled || ref.startsWith("skill:${c.config.agent.name}:") || ref.startsWith("plugin:device:") || ref.startsWith("plugin:appfunction:"))
        val refs = if (enabled) c.draft.capabilities + ref else c.draft.capabilities.filterNot {
            it == ref || ref.startsWith("plugin:device:") && it.startsWith("$ref:")
        }.toSet()
        val creator = if (!enabled && c.creator == ref) null else c.creator
        require((refs + listOfNotNull(creator)).size <= 24)
        val clearTemplate = creator == null && c.creator != null && AppStrings.isUneditedSkillCreationPrompt(c.draft.text)
        c.copy(draft = c.draft.copy(revision = c.draft.revision + 1, capabilities = refs,
            text = if (clearTemplate) "" else c.draft.text,
            selectionStart = if (clearTemplate) 0 else c.draft.selectionStart,
            selectionEnd = if (clearTemplate) 0 else c.draft.selectionEnd), creator = creator)
    }
    suspend fun editDraft(id: ConversationId, text: String, selectionStart: Int, selectionEnd: Int): Draft = db.withTransaction {
        val c = requireNotNull(dao.conversation(id.value)).domain()
        val draft = if (c.draft.text == text && c.draft.selectionStart == selectionStart && c.draft.selectionEnd == selectionEnd) c.draft
            // Moving the cursor does not create a new message. Acceptance must still clear
            // the submitted content; only new text should protect this draft from that clear.
            else c.draft.copy(revision = c.draft.revision + if (c.draft.text != text) 1 else 0,
                text = text, selectionStart = selectionStart.coerceIn(0, text.length), selectionEnd = selectionEnd.coerceIn(0, text.length))
        dao.save(c.copy(draft = draft).row()); draft
    }
    private suspend fun mutate(id: ConversationId, transform: (Conversation) -> Conversation) = db.withTransaction {
        dao.conversation(id.value)?.let { dao.save(transform(it.domain()).row()) }; Unit
    }
}
