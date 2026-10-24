package com.github.ytlog.mobby.android.programs.capability

import com.github.ytlog.mobby.android.programs.call.CallContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

enum class CapabilityKind { QUERY, COMMAND, EVENT, SESSION, HANDOFF }

/** Shape of the scope a grant of this capability may carry. */
enum class ScopeKind { NONE, PACKAGES, DOMAINS, MODELS, RECORDS, DIRECTORIES }

enum class SideEffect { READ, WRITE, SEND, DELETE, CONTROL, NETWORK }

/** Where a call may come from; handoffs need a person looking at the program UI. */
enum class Surface { BACKGROUND, UI }

data class Prerequisite(val kind: String, val value: String) {
    companion object {
        /** Works only inside the program's own space; a dry run gives it a throwaway space. */
        val PROGRAM_SPACE = Prerequisite("program-space", "own")
    }
}

data class CapabilityLimits(val maxArgumentBytes: Int, val callsPerMinute: Int)

data class CapabilityDescriptor(
    val id: String,
    val version: Int,
    val kind: CapabilityKind,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val scope: ScopeKind,
    val sideEffects: Set<SideEffect>,
    val prerequisites: List<Prerequisite>,
    val surfaces: Set<Surface>,
    val timeoutMillis: Long,
    val cancellable: Boolean,
    val limits: CapabilityLimits,
)

enum class AvailabilityReason { DEVICE_UNSUPPORTED, SYSTEM_PERMISSION_MISSING, SOURCE_DISCONNECTED, CONDITION_UNMET }

sealed interface Availability {
    object Available : Availability
    data class Unavailable(val reason: AvailabilityReason) : Availability
}

/** Error codes of the program API. Names are the wire values. */
enum class ProgramErrorCode {
    UNSUPPORTED, INVALID_ARGUMENT, PERMISSION_DENIED, SCOPE_DENIED, SOURCE_DISCONNECTED, CONDITION_UNMET,
    USER_INTERACTION_REQUIRED, TARGET_NOT_UNIQUE, STALE_OBSERVATION, REQUEST_CONFLICT, RESOURCE_MISSING,
    QUOTA_EXCEEDED, CANCELLED, TIMEOUT, EFFECT_UNKNOWN, FAILED,
}

/** What is known about side effects; SUBMITTED and PARTIAL keep device facts such as an SMS handed to the radio. */
enum class Effect { NONE, SUBMITTED, PARTIAL, CONFIRMED, UNKNOWN }

/** A failed call. [effect] states what is known about side effects before the failure. */
class CapabilityException(
    val code: ProgramErrorCode,
    val reason: String,
    val effect: Effect = Effect.NONE,
) : RuntimeException("$code: $reason")

/** Per-program directories a handler may use; never another program's. */
data class ProgramSpace(val programId: String, val files: File, val temp: File)

class CapabilityCall(
    val context: CallContext,
    val args: JsonObject,
    val space: ProgramSpace,
    val isCancelled: () -> Boolean,
)

/** Facts kept in the operation record; never request or response bodies. */
data class OperationAudit(val host: String? = null, val method: String? = null, val status: Int? = null,
    val bytesOut: Long? = null, val bytesIn: Long? = null)

data class CapabilityOutcome(val effect: Effect, val result: JsonElement, val audit: OperationAudit? = null)

/** What one call touches, so the grant's scope can be checked before dispatch. */
data class CallScope(val packageName: String? = null, val host: String? = null, val method: String? = null,
    val insecureHttp: Boolean = false, val model: String? = null) {
    companion object { val NONE = CallScope() }
}

interface CapabilityHandler {
    val descriptor: CapabilityDescriptor
    fun availability(): Availability
    /** Extracts the scope a call needs; throws [CapabilityException] for arguments that cannot be scoped. */
    fun callScope(args: JsonObject): CallScope = CallScope.NONE
    suspend fun invoke(call: CapabilityCall): CapabilityOutcome
}

/** Event capabilities also own the shape of their subscription filters. */
interface EventCapability {
    /** Problems with [filter]; it may not reach beyond what the program requested for this capability. */
    fun validateFilter(filter: JsonObject, requested: com.github.ytlog.mobby.android.programs.permission.Scope): List<String>
}
