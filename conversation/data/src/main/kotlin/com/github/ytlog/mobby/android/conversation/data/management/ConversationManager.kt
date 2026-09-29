package com.github.ytlog.mobby.android.conversation.data.management

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.conversation.data.*
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayProfile
import com.github.ytlog.mobby.android.localization.AppStrings

/** Owns conversation identity, configuration, visibility and selection. */
internal class ConversationManager(private val db: ConversationDatabase, private val now: () -> Long, private val newId: () -> String) {
    private val dao = db.dao()

    suspend fun create(config: NextTurnConfig): ConversationId = db.withTransaction {
        val c = Conversation(ConversationId(newId()), config.copy(workspace = "default"), updatedAt = now())
        dao.save(c.row()); dao.select(SelectionRow(conversationId = c.id.value)); c.id
    }

    suspend fun createSkill(source: ConversationId, creator: String): ConversationId = db.withTransaction {
        val old = requireNotNull(dao.conversation(source.value)).domain()
        require(creator.startsWith("skill:${old.config.agent.name}:"))
        val created = requireNotNull(ConversationRules.createSkillConversation(old, ConversationId(newId()), creator))
            .copy(title = AppStrings.createSkill, updatedAt = now())
        dao.save(created.row()); dao.select(SelectionRow(conversationId = created.id.value)); created.id
    }

    suspend fun select(id: ConversationId): Boolean {
        val c = dao.conversation(id.value)?.domain() ?: return false
        if (c.deleted) return false
        dao.select(SelectionRow(conversationId = id.value))
        return true
    }

    suspend fun configure(id: ConversationId, config: NextTurnConfig): ConversationId = db.withTransaction {
        val old = requireNotNull(dao.conversation(id.value)).domain()
        val changed = ConversationRules.applyConfig(old, config.copy(workspace = old.config.workspace)).copy(updatedAt = now())
        dao.save(changed.row()); dao.select(SelectionRow(conversationId = changed.id.value)); changed.id
    }

    suspend fun updateGateway(profile: GatewayProfile) = db.withTransaction {
        for (row in dao.allConversations()) {
            val c = row.domain()
            if (c.config.agent == profile.agent && c.config.gatewayProfile == profile.id) {
                val retained = profile.offers(c.config.model)
                dao.save(c.copy(config = c.config.copy(model = if (retained) c.config.model else profile.model,
                    gatewayVersion = profile.version, reasoning = c.config.reasoning.takeIf { retained })).row())
            }
        }
    }

    suspend fun rename(id: ConversationId, title: String): OperationResult {
        mutate(id) { it.copy(title = title) }
        return OperationResult.Done
    }
    suspend fun pin(id: ConversationId) = mutate(id) { it.copy(pinned = !it.pinned) }
    suspend fun anchor(id: ConversationId, messageId: String?, offset: Int) = mutate(id) { it.copy(anchor = messageId, anchorOffset = offset) }
    suspend fun archive(id: ConversationId, archived: Boolean) = changeVisibility(id) { it.copy(archived = archived) }
    suspend fun delete(id: ConversationId, deleted: Boolean) = changeVisibility(id) { it.copy(deleted = deleted) }

    private suspend fun changeVisibility(id: ConversationId, transform: (Conversation) -> Conversation): OperationResult = db.withTransaction {
        if (dao.conversationTurns(id.value).any { it.occupied || it.queued }) return@withTransaction OperationResult.Failed(AppStrings.runningOrQueuedConversationsCannotBeArchivedOrDeleted)
        val c = dao.conversation(id.value)?.domain() ?: return@withTransaction OperationResult.Failed(AppStrings.conversationNotFound)
        dao.save(transform(c).row())
        OperationResult.Done
    }

    private suspend fun mutate(id: ConversationId, transform: (Conversation) -> Conversation) = db.withTransaction {
        dao.conversation(id.value)?.let { dao.save(transform(it.domain()).row()) }; Unit
    }
}
