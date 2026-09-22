package com.mobby.interaction.data

import com.mobby.interaction.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
@Serializable internal data class StoredImport(val id: String, val workspace: String, val location: String, val error: String? = null) {
    fun domain() = PendingAttachment(id, workspace, location, error)
}
@Serializable internal data class StoredConversation(
    val id: String, val agent: String, val model: String, val reasoning: String?, val workspace: String,
    val gatewayProfile: String, val gatewayVersion: Long,
    val text: String = "", val draftRevision: Long = 0, val selectionStart: Int = text.length, val selectionEnd: Int = selectionStart,
    val attachments: List<String> = emptyList(), val capabilities: Set<String> = emptySet(),
    val hasTurns: Boolean = false, val session: String? = null, val title: String = "新对话",
    val pinned: Boolean = false, val project: String? = null, val archived: Boolean = false, val deleted: Boolean = false,
    val anchor: String? = null, val anchorOffset: Int = 0, val updatedAt: Long = 0, val creator: String? = null, val pendingAttachment: StoredImport? = null,
    val sessions: Map<String, String> = emptyMap()
) {
    fun domain() = Conversation(ConversationId(id), NextTurnConfig(AgentId.valueOf(agent), model, reasoning, workspace, gatewayProfile, gatewayVersion),
        Draft(draftRevision, text, selectionStart, selectionEnd, attachments, capabilities, pendingAttachment?.domain()), hasTurns, session, title, pinned, project, archived, deleted, anchor, anchorOffset, updatedAt, creator,
        sessions = sessions.mapNotNull { (key, value) -> runCatching { AgentId.valueOf(key) to value }.getOrNull() }.toMap())
    companion object {
        fun from(c: Conversation) = StoredConversation(c.id.value, c.config.agent.name, c.config.model, c.config.reasoning, c.config.workspace,
            c.config.gatewayProfile, c.config.gatewayVersion, c.draft.text, c.draft.revision, c.draft.selectionStart, c.draft.selectionEnd,
            c.draft.attachments, c.draft.capabilities, c.hasTurns, c.session, c.title, c.pinned, c.project, c.archived, c.deleted, c.anchor, c.anchorOffset, c.updatedAt, c.creator, c.draft.pendingAttachment?.let { StoredImport(it.id, it.workspace, it.location, it.error) },
            c.sessions.mapKeys { it.key.name })
    }
}
internal fun Conversation.row() = ConversationRow(id.value, storageJson.encodeToString(StoredConversation.from(this)), updatedAt)
internal fun ConversationRow.domain() = storageJson.decodeFromString<StoredConversation>(body).domain()
internal fun TurnRow.execution(): TurnExecution {
    val frozen = storageJson.decodeFromString<StoredConversation>(frozen).domain()
    return TurnExecution(TurnId(id), frozen.id, frozen.draft, frozen.config, frozen.session, frozen.creator != null)
}
