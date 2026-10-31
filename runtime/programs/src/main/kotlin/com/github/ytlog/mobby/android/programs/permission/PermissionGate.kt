package com.github.ytlog.mobby.android.programs.permission

import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.capability.*

data class Grant(
    val programId: String,
    val capability: String,
    val granted: Scope,
    val requested: Scope,
    val enabled: Boolean,
    val required: Boolean,
    val expiresAt: Long?,
    val revision: Long,
)

/** Layers in checking order; a denial names the first one that fails. */
enum class Layer(val wire: String) { SYSTEM("system"), GLOBAL("global"), PROGRAM("program"), CONTEXT("context") }

enum class DenyReason(val wire: String) {
    DEVICE_UNSUPPORTED("device_unsupported"), SYSTEM_PERMISSION_MISSING("system_permission_missing"),
    SOURCE_DISCONNECTED("source_disconnected"), CONDITION_UNMET("condition_unmet"),
    GLOBAL_OFF("global_off"), NOT_REQUESTED("not_requested"), PROGRAM_OFF("program_off"), EXPIRED("expired"),
    PACKAGE_NOT_GRANTED("package_not_granted"), DOMAIN_NOT_GRANTED("domain_not_granted"),
    METHOD_NOT_GRANTED("method_not_granted"), HTTP_NOT_GRANTED("http_not_granted"), MODEL_NOT_GRANTED("model_not_granted"),
    SURFACE_NOT_ALLOWED("surface_not_allowed"), NOT_ALLOWED_IN_DRY_RUN("not_allowed_in_dry_run"),
}

sealed interface GateDecision {
    data class Allowed(val grantRevision: Long) : GateDecision
    data class Denied(val layer: Layer, val reason: DenyReason) : GateDecision {
        val code get() = when {
            layer == Layer.SYSTEM && reason == DenyReason.SOURCE_DISCONNECTED -> ProgramErrorCode.SOURCE_DISCONNECTED
            layer == Layer.SYSTEM && reason == DenyReason.CONDITION_UNMET -> ProgramErrorCode.CONDITION_UNMET
            layer == Layer.SYSTEM && reason == DenyReason.DEVICE_UNSUPPORTED -> ProgramErrorCode.UNSUPPORTED
            reason.wire.endsWith("_not_granted") -> ProgramErrorCode.SCOPE_DENIED
            else -> ProgramErrorCode.PERMISSION_DENIED
        }
    }
}

/** Immutable view of every grant and switch; replaced as a whole after each change. */
class PermissionSnapshot(grants: List<Grant>, private val switches: Map<String, Boolean>) {
    private val byProgram = grants.groupBy { it.programId }.mapValues { (_, list) -> list.associateBy { it.capability } }
    fun grant(programId: String, capability: String): Grant? = byProgram[programId]?.get(capability)
    fun grants(programId: String): Collection<Grant> = byProgram[programId]?.values.orEmpty()
    fun switchOn(capability: String) = switches[capability] ?: true
    val nextExpiry: Long? get() = byProgram.values.flatMap { it.values }.mapNotNull { it.expiresAt }.minOrNull()

    companion object { val EMPTY = PermissionSnapshot(emptyList(), emptyMap()) }
}

/** Program-side state of one grant as the user and the program see it. */
enum class PermissionState(val wire: String) {
    GRANTED("granted"), UNAVAILABLE("unavailable"), SYSTEM_MISSING("system_missing"), GLOBAL_OFF("global_off"),
    PROGRAM_OFF("program_off"), EXPIRED("expired"),
}

class PermissionGate(private val registry: CapabilityRegistry, private val clock: () -> Long) {
    @Volatile var snapshot: PermissionSnapshot = PermissionSnapshot.EMPTY

    fun check(context: CallContext, descriptor: CapabilityDescriptor, scope: CallScope): GateDecision {
        val current = snapshot
        when (val availability = registry[descriptor.id]?.availability() ?: Availability.Unavailable(AvailabilityReason.DEVICE_UNSUPPORTED)) {
            is Availability.Unavailable -> return GateDecision.Denied(Layer.SYSTEM, when (availability.reason) {
                AvailabilityReason.DEVICE_UNSUPPORTED -> DenyReason.DEVICE_UNSUPPORTED
                AvailabilityReason.SYSTEM_PERMISSION_MISSING -> DenyReason.SYSTEM_PERMISSION_MISSING
                AvailabilityReason.SOURCE_DISCONNECTED -> DenyReason.SOURCE_DISCONNECTED
                AvailabilityReason.CONDITION_UNMET -> DenyReason.CONDITION_UNMET
            })
            Availability.Available -> Unit
        }
        if (!current.switchOn(descriptor.id)) return GateDecision.Denied(Layer.GLOBAL, DenyReason.GLOBAL_OFF)
        if (descriptor.implicit) return if (context.surface in descriptor.surfaces) GateDecision.Allowed(0)
            else GateDecision.Denied(Layer.CONTEXT, DenyReason.SURFACE_NOT_ALLOWED)
        // A dry run is checked against what the package requests, as if the user had granted it.
        val grant = context.dryRun?.let { dry -> dry.requested[descriptor.id]?.let { Grant(context.programId, descriptor.id, it, it, true, false, null, 0) } }
            ?: (if (context.dryRun != null) null else current.grant(context.programId, descriptor.id))
            ?: return GateDecision.Denied(Layer.PROGRAM, DenyReason.NOT_REQUESTED)
        if (!grant.enabled) return GateDecision.Denied(Layer.PROGRAM, DenyReason.PROGRAM_OFF)
        if (grant.expiresAt != null && clock() >= grant.expiresAt) return GateDecision.Denied(Layer.PROGRAM, DenyReason.EXPIRED)
        scopeDenial(descriptor.scope, grant.granted, scope)?.let { return GateDecision.Denied(Layer.PROGRAM, it) }
        if (context.surface !in descriptor.surfaces) return GateDecision.Denied(Layer.CONTEXT, DenyReason.SURFACE_NOT_ALLOWED)
        // A handoff needs a person; a session is checked like any call and then simulated, so the rest of a handler can run.
        if (context.dryRun != null && descriptor.kind == CapabilityKind.HANDOFF)
            return GateDecision.Denied(Layer.CONTEXT, DenyReason.NOT_ALLOWED_IN_DRY_RUN)
        return GateDecision.Allowed(grant.revision)
    }

    /** True when [revision] is still the current revision of this grant; checked again right before dispatch. */
    fun stillCurrent(programId: String, capability: String, revision: Long): Boolean =
        registry[capability]?.descriptor?.implicit == true || revision == 0L || snapshot.grant(programId, capability)?.revision == revision

    fun state(grant: Grant): PermissionState {
        val handler = registry[grant.capability] ?: return PermissionState.UNAVAILABLE
        return when {
            (handler.availability() as? Availability.Unavailable)?.reason == AvailabilityReason.SYSTEM_PERMISSION_MISSING -> PermissionState.SYSTEM_MISSING
            handler.availability() is Availability.Unavailable -> PermissionState.UNAVAILABLE
            !snapshot.switchOn(grant.capability) -> PermissionState.GLOBAL_OFF
            !grant.enabled -> PermissionState.PROGRAM_OFF
            grant.expiresAt != null && clock() >= grant.expiresAt -> PermissionState.EXPIRED
            else -> PermissionState.GRANTED
        }
    }

    private fun scopeDenial(kind: ScopeKind, granted: Scope, call: CallScope): DenyReason? = when (kind) {
        ScopeKind.PACKAGES -> if (call.packageName == null || !granted.allowsPackage(call.packageName)) DenyReason.PACKAGE_NOT_GRANTED else null
        ScopeKind.DOMAINS -> when {
            call.host == null || !granted.allowsHost(call.host) -> DenyReason.DOMAIN_NOT_GRANTED
            call.method != null && !granted.allowsMethod(call.method) -> DenyReason.METHOD_NOT_GRANTED
            call.insecureHttp && !granted.insecureHttp -> DenyReason.HTTP_NOT_GRANTED
            else -> null
        }
        ScopeKind.MODELS -> if (call.model == null || !granted.allowsModel(call.model)) DenyReason.MODEL_NOT_GRANTED else null
        ScopeKind.NONE, ScopeKind.RECORDS, ScopeKind.DIRECTORIES -> null
    }
}
