package com.github.ytlog.mobby.android.programs.base.log

import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.store.LogRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `log`: short entries in the program's run records; long data is cut and marked. */
class LogCapability(private val dao: ProgramsDao, private val clock: () -> Long, private val keep: Int = 500) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("log", 1, CapabilityKind.COMMAND,
        schema(listOf("message"), "message" to stringType(1_000), "data" to buildJsonObject { put("description", "any JSON value") }),
        buildJsonObject { put("type", "object") }, ScopeKind.NONE, setOf(SideEffect.WRITE), listOf(Prerequisite.PROGRAM_SPACE),
        setOf(Surface.BACKGROUND, Surface.UI), 5_000, false, CapabilityLimits(16 * 1024, 60), implicit = true)

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val message = call.args.text("message") ?: invalid("message_required")
        val data = call.args["data"]?.toString()?.let { if (it.length > MAX_DATA) it.take(MAX_DATA) + "…truncated" else it }
        dao.insertLog(LogRow(programId = call.context.programId, caller = call.context.caller, at = clock(), message = message, dataJson = data))
        dao.pruneLogs(call.context.programId, keep)
        return CapabilityOutcome(Effect.CONFIRMED, JsonNull)
    }

    private companion object { const val MAX_DATA = 4_096 }
}
