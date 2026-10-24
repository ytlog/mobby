package com.github.ytlog.mobby.android.programs.permission

import com.github.ytlog.mobby.android.programs.store.GrantRow
import com.github.ytlog.mobby.android.programs.store.PermissionRequestRow
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import com.github.ytlog.mobby.android.programs.store.SwitchRow
import java.util.UUID

/** User-side changes to mobby's permission layer. Each change bumps the grant revision and reloads the gate. */
class PermissionManager(private val dao: ProgramsDao, private val gate: PermissionGate, private val clock: () -> Long) {
    suspend fun reload() {
        gate.snapshot = PermissionSnapshot(dao.allGrants().map(::grant), dao.switches().associate { it.capability to it.enabled })
    }

    /** Scope may only narrow what the program requested; it is never widened here. */
    suspend fun setGrant(programId: String, capability: String, enabled: Boolean, scope: Scope?, expiresAt: Long?): Boolean {
        val row = dao.grants(programId).firstOrNull { it.capability == capability } ?: return false
        val requested = Scope.fromJson(row.requestedScope)
        val granted = scope ?: Scope.fromJson(row.grantedScope)
        require(granted.within(requested)) { "Grant scope exceeds the request" }
        require(expiresAt == null || expiresAt > clock()) { "Expiry must be in the future" }
        dao.upsertGrant(row.copy(grantedScope = granted.toJson().toString(), enabled = enabled, source = "user",
            grantedAt = if (enabled && !row.enabled) clock() else row.grantedAt, expiresAt = expiresAt, revision = row.revision + 1))
        reload()
        return true
    }

    suspend fun setSwitch(capability: String, enabled: Boolean) {
        dao.upsertSwitch(SwitchRow(capability, enabled, clock()))
        reload()
    }

    /** A program may ask for a capability it declared; the request waits for the user. */
    suspend fun request(programId: String, capability: String, scope: Scope): String {
        val row = dao.grants(programId).firstOrNull { it.capability == capability }
            ?: throw IllegalArgumentException("Capability was not declared in the manifest")
        require(scope.within(Scope.fromJson(row.requestedScope))) { "Requests cannot exceed the manifest; update the program instead" }
        val id = UUID.randomUUID().toString()
        dao.insertRequest(PermissionRequestRow(id, programId, capability, scope.toJson().toString(), "pending", clock()))
        return id
    }

    suspend fun resolveRequest(requestId: String, approved: Boolean, programId: String, capability: String, scope: Scope) {
        if (approved) check(setGrant(programId, capability, true, scope, null)) { "Grant no longer exists" }
        dao.setRequestState(requestId, if (approved) "approved" else "declined")
    }

    companion object {
        fun grant(row: GrantRow) = Grant(row.programId, row.capability, Scope.fromJson(row.grantedScope), Scope.fromJson(row.requestedScope),
            row.enabled, row.required, row.expiresAt, row.revision)
    }
}
