package com.mobby.interaction.domain

import kotlinx.coroutines.flow.StateFlow

enum class Appearance { SYSTEM, LIGHT, DARK }
interface PreferencePort {
    val appearance: StateFlow<Appearance>
    suspend fun setAppearance(value: Appearance): OperationResult
}
data class SearchHit(val turnId: TurnId, val messageId: String?, val text: String) {
    val targetKey: String get() = if (messageId == null) "user:${turnId.value}" else "message:${turnId.value}:$messageId"
}
/** Search only user-visible conversation messages, excluding process logs and configuration. */
object ConversationSearch {
    fun find(detail: ConversationDetail, query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return detail.turns.flatMap { turn ->
            listOf(SearchHit(turn.id, null, turn.userText)) + turn.messages.map { SearchHit(turn.id, it.id, it.text) }
        }.filter { it.text.contains(query, ignoreCase = true) }
    }
}
