package com.github.ytlog.mobby.android.programs.event

import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import kotlinx.serialization.json.*

/**
 * `entry.share` and `entry.shortcut`: the user hands a program text shared from another app, or taps its
 * home screen shortcut. Event sources only; mobby's fixed share and shortcut components dispatch them by program ID.
 */
class EntryCapability(id: String) : CapabilityHandler, EventCapability {
    init { require(id == SHARE || id == SHORTCUT) }

    override val descriptor = CapabilityDescriptor(id, 1, CapabilityKind.EVENT,
        buildJsonObject { put("type", "object") }, buildJsonObject { put("type", "object") }, ScopeKind.NONE, emptySet(), emptyList(),
        setOf(Surface.BACKGROUND), 1_000, false, CapabilityLimits(1024, 60))

    override fun availability(): Availability = Availability.Available

    override fun validateFilter(filter: JsonObject, requested: Scope): List<String> =
        if (filter.isEmpty()) emptyList() else listOf("${descriptor.id} takes no filter; use {}")

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome =
        throw CapabilityException(ProgramErrorCode.UNSUPPORTED, "event_capabilities_are_subscribed_not_called")

    companion object {
        const val SHARE = "entry.share"
        const val SHORTCUT = "entry.shortcut"
        /** Shared text and subject together, in UTF-8. */
        const val MAX_SHARED_BYTES = 32 * 1024

        fun fits(text: String, subject: String?) = text.toByteArray().size + (subject?.toByteArray()?.size ?: 0) <= MAX_SHARED_BYTES
    }
}
