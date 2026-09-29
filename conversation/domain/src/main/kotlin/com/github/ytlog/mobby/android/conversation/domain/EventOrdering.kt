package com.github.ytlog.mobby.android.conversation.domain

/** The data adapter maps Runtime envelopes to this domain ordering decision.
 * Only APPLY may update a projection, in the same transaction as advancing its cursor.
 * A gap requires replay/resync; it must not append text out of order.
 */
enum class EventOrdering { APPLY, DUPLICATE, GAP, WRONG_EXECUTION }
data class ProjectionCursor(val executionId: ExecutionId, val sequence: Long) {
    init { require(sequence >= 0) }
    fun classify(execution: ExecutionId, incomingSequence: Long): EventOrdering = when {
        execution != executionId -> EventOrdering.WRONG_EXECUTION
        incomingSequence <= sequence -> EventOrdering.DUPLICATE
        incomingSequence == sequence + 1 -> EventOrdering.APPLY
        else -> EventOrdering.GAP
    }
}
