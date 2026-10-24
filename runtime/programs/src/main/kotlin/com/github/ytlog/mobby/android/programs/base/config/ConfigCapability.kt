package com.github.ytlog.mobby.android.programs.base.config

import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `config`: the program's own config values; secret fields appear only by name. */
class ConfigCapability(private val store: ConfigStore) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("config", 1, CapabilityKind.QUERY,
        buildJsonObject { put("type", "object"); put("additionalProperties", false) }, buildJsonObject { put("type", "object") },
        ScopeKind.NONE, setOf(SideEffect.READ), listOf(Prerequisite.PROGRAM_SPACE), setOf(Surface.BACKGROUND, Surface.UI),
        5_000, false, CapabilityLimits(256, 120), implicit = true)

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall) = CapabilityOutcome(Effect.NONE, store.values(call.context.programId))
}
