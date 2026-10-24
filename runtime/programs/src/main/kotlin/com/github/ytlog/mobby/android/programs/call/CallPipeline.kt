package com.github.ytlog.mobby.android.programs.call

import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.GateDecision
import com.github.ytlog.mobby.android.programs.permission.PermissionGate
import com.github.ytlog.mobby.android.programs.program.ProgramLayout
import com.github.ytlog.mobby.android.programs.store.OperationRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class CallStatus(val wire: String) {
    ACCEPTED("accepted"), SUCCEEDED("succeeded"), FAILED("failed"), CANCELLED("cancelled"),
    TIMEOUT("timeout"), UNKNOWN("unknown"), SIMULATED("simulated");
    companion object { fun of(wire: String) = values().first { it.wire == wire } }
}

data class CallRequest(val requestId: String, val capability: String, val args: JsonObject, val deadlineMs: Long? = null)

data class CallError(val code: ProgramErrorCode, val layer: String?, val reason: String)

data class CallResponse(
    val requestId: String,
    val operationId: String?,
    val status: CallStatus,
    val effect: Effect,
    /** Null for replays: results are returned once and never stored. */
    val result: JsonElement?,
    val error: CallError?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("requestId", requestId)
        operationId?.let { put("operationId", it) }
        put("status", status.wire)
        put("effect", effect.name.lowercase())
        result?.let { put("result", it) }
        error?.let { e -> put("error", buildJsonObject { put("code", e.code.name); e.layer?.let { put("layer", it) }; put("reason", e.reason) }) }
    }
}

/**
 * The one path every program call takes, from the background API or the UI bridge:
 * dedupe, validate, gate, record before dispatch, recheck the grant, dispatch, record the receipt.
 */
class CallPipeline(
    private val registry: CapabilityRegistry,
    private val gate: PermissionGate,
    private val dao: ProgramsDao,
    private val layout: ProgramLayout,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun call(context: CallContext, request: CallRequest, isCancelled: () -> Boolean = { false }): CallResponse {
        if (!request.requestId.matches(REQUEST_ID)) return reject(request, ProgramErrorCode.INVALID_ARGUMENT, "invalid_request_id")
        val handler = registry[request.capability] ?: return reject(request, ProgramErrorCode.UNSUPPORTED, "unknown_capability")
        val descriptor = handler.descriptor
        val fingerprint = fingerprint(request)
        dao.operation(context.caller, request.requestId)?.let { existing ->
            return if (existing.fingerprint == fingerprint) replay(existing)
            else reject(request, ProgramErrorCode.REQUEST_CONFLICT, "request_id_reused_with_other_arguments")
        }
        if (request.args.toString().length > descriptor.limits.maxArgumentBytes) return reject(request, ProgramErrorCode.INVALID_ARGUMENT, "arguments_too_large")
        SchemaCheck.problems(descriptor.inputSchema, request.args).firstOrNull()?.let { return reject(request, ProgramErrorCode.INVALID_ARGUMENT, it) }
        val scope = try { handler.callScope(request.args) } catch (error: CapabilityException) { return reject(request, error.code, error.reason) }
        val accepted = OperationRow(newId(), context.programId, context.caller, request.requestId, fingerprint, descriptor.id, 0, clock(),
            status = CallStatus.ACCEPTED.wire, effect = "none")
        val decision = gate.check(context, descriptor, scope)
        if (decision is GateDecision.Denied) return finish(accepted, CallStatus.FAILED, Effect.NONE, null,
            CallError(decision.code, decision.layer.wire, decision.reason.wire), insert = true)
        val revision = (decision as GateDecision.Allowed).grantRevision
        val row = accepted.copy(grantRevision = revision)
        dao.insertOperation(row)
        context.dryRun?.let { dry -> if (!dry.permitsDispatch(descriptor)) return finish(row, CallStatus.SIMULATED, Effect.NONE, JsonNull, null) }
        // The grant may have changed while the call was being validated and recorded.
        if (!gate.stillCurrent(context.programId, descriptor.id, revision)) {
            val again = gate.check(context, descriptor, scope)
            if (again is GateDecision.Denied) return finish(row, CallStatus.FAILED, Effect.NONE, null, CallError(again.code, again.layer.wire, again.reason.wire))
        }
        val dispatched = row.copy(dispatchedAt = clock())
        dao.updateOperation(dispatched)
        val deadline = minOf(descriptor.timeoutMillis, request.deadlineMs ?: Long.MAX_VALUE).coerceAtLeast(1)
        return try {
            val outcome = withTimeout(deadline) { handler.invoke(CapabilityCall(context, request.args, space(context), isCancelled)) }
            finish(dispatched, CallStatus.SUCCEEDED, outcome.effect, outcome.result, null, audit = outcome.audit)
        } catch (error: CapabilityException) {
            val status = when (error.code) { ProgramErrorCode.CANCELLED -> CallStatus.CANCELLED; ProgramErrorCode.TIMEOUT -> CallStatus.TIMEOUT
                ProgramErrorCode.EFFECT_UNKNOWN -> CallStatus.UNKNOWN; else -> CallStatus.FAILED }
            finish(dispatched, status, error.effect, null, CallError(error.code, null, error.reason))
        } catch (_: TimeoutCancellationException) {
            // Past dispatch, a command may already have had its effect.
            finish(dispatched, CallStatus.TIMEOUT, if (descriptor.kind == CapabilityKind.QUERY) Effect.NONE else Effect.UNKNOWN, null,
                CallError(ProgramErrorCode.TIMEOUT, null, "deadline_exceeded"))
        } catch (cancelled: CancellationException) {
            finish(dispatched, CallStatus.CANCELLED, if (descriptor.kind == CapabilityKind.QUERY) Effect.NONE else Effect.UNKNOWN, null,
                CallError(ProgramErrorCode.CANCELLED, null, "cancelled"))
            throw cancelled
        }
    }

    /** The recorded facts of an earlier call by the same caller. */
    suspend fun find(context: CallContext, requestId: String): CallResponse? = dao.operation(context.caller, requestId)?.let(::replay)

    /** Program-space capabilities run against dry-run storage; device queries only when the chat run allowed them; the rest is simulated. */
    private fun DryRunScope.permitsDispatch(descriptor: CapabilityDescriptor) =
        descriptor.prerequisites.any { it == Prerequisite.PROGRAM_SPACE } ||
            (descriptor.kind == CapabilityKind.QUERY && descriptor.prerequisites.any { it.kind == "device-plugin" } && descriptor.id in allowedQueries)

    private fun space(context: CallContext): ProgramSpace {
        val base = context.dryRun?.let { File(layout.data(context.programId), "dry-run/${context.runId ?: context.uiSessionId}") }
        return ProgramSpace(context.programId, base?.let { File(it, "files") } ?: layout.files(context.programId),
            base?.let { File(it, "tmp") } ?: layout.temp(context.programId)).also { it.files.mkdirs(); it.temp.mkdirs() }
    }

    private suspend fun finish(row: OperationRow, status: CallStatus, effect: Effect, result: JsonElement?, error: CallError?,
        insert: Boolean = false, audit: OperationAudit? = null): CallResponse {
        val done = row.copy(completedAt = clock(), status = status.wire, effect = effect.name.lowercase(),
            errorCode = error?.code?.name, errorReason = error?.reason,
            netHost = audit?.host, netMethod = audit?.method, netStatus = audit?.status, netBytesOut = audit?.bytesOut, netBytesIn = audit?.bytesIn)
        if (insert) dao.insertOperation(done) else dao.updateOperation(done)
        return CallResponse(row.requestId, row.operationId, status, effect, result, error)
    }

    private fun replay(row: OperationRow) = CallResponse(row.requestId, row.operationId, CallStatus.of(row.status),
        Effect.valueOf(row.effect.uppercase()), null, row.errorCode?.let { CallError(ProgramErrorCode.valueOf(it), null, row.errorReason.orEmpty()) })

    private fun reject(request: CallRequest, code: ProgramErrorCode, reason: String) =
        CallResponse(request.requestId, null, CallStatus.FAILED, Effect.NONE, null, CallError(code, null, reason))

    companion object {
        val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,128}")

        fun fingerprint(request: CallRequest): String = MessageDigest.getInstance("SHA-256")
            .digest((request.capability + "\u0000" + canonical(request.args)).toByteArray())
            .joinToString("") { "%02x".format(it) }

        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ":" + canonical(v) }
            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
            else -> value.toString()
        }
    }
}
