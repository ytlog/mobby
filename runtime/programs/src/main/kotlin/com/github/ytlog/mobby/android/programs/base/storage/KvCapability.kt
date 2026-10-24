package com.github.ytlog.mobby.android.programs.base.storage

import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.store.KvRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import kotlinx.serialization.json.*

/** `storage.kv`: JSON values by key in programs.db; dry runs use their own namespace. */
class KvCapability(private val dao: ProgramsDao, private val clock: () -> Long, private val limits: StorageLimits = StorageLimits.DEFAULT) : CapabilityHandler {
    override val descriptor = storageDescriptor("storage.kv", CapabilityKind.COMMAND,
        schema(listOf("op", "key"), "op" to enumType("get", "set", "delete"), "key" to stringType(256),
            "value" to buildJsonObject { put("description", "any JSON value") }),
        setOf(SideEffect.READ, SideEffect.WRITE))

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val owner = owner(call)
        val key = call.args.text("key") ?: invalid("key_required")
        return when (call.args.text("op")) {
            "get" -> CapabilityOutcome(Effect.NONE, dao.kv(owner, key)?.let { Json.parseToJsonElement(it.value) } ?: JsonNull)
            "set" -> {
                val value = call.args["value"] ?: invalid("value_required")
                val encoded = value.toString()
                if (encoded.length > limits.kvValueBytes) invalid("value_too_large")
                val previous = dao.kv(owner, key)?.let { it.key.length + it.value.length } ?: 0
                if (dao.kvBytes(owner) - previous + key.length + encoded.length > limits.kvBytes)
                    throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "kv_quota")
                dao.upsertKv(KvRow(owner, key, encoded, clock()))
                CapabilityOutcome(Effect.CONFIRMED, JsonNull)
            }
            "delete" -> CapabilityOutcome(if (dao.deleteKv(owner, key) > 0) Effect.CONFIRMED else Effect.NONE, JsonNull)
            else -> invalid("unknown_op")
        }
    }

    companion object {
        fun owner(call: CapabilityCall) = call.context.dryRun?.let { "${call.context.programId}#dry:${call.context.runId}" } ?: call.context.programId
    }
}
