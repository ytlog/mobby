package com.github.ytlog.mobby.android.programs.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One event delivered to a program. [handler] names the program function to call. */
data class ProgramEvent(
    val eventId: String,
    val type: String,
    val handler: String,
    val subscriptionId: String?,
    val observedAt: Long,
    val payload: JsonObject,
) {
    fun toJson() = buildJsonObject {
        put("eventId", eventId); put("type", type); put("handler", handler)
        subscriptionId?.let { put("subscription", it) }
        put("observedAt", observedAt); put("payload", payload)
    }
}
