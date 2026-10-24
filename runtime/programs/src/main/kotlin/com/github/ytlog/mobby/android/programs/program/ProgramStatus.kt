package com.github.ytlog.mobby.android.programs.program

import com.github.ytlog.mobby.android.programs.base.config.ConfigStore
import com.github.ytlog.mobby.android.programs.permission.PermissionGate
import com.github.ytlog.mobby.android.programs.permission.PermissionManager
import com.github.ytlog.mobby.android.programs.permission.PermissionState
import com.github.ytlog.mobby.android.programs.store.ProgramsDao

/** One computation of program availability, shared by event routing and the program center. */
class ProgramStatus(private val dao: ProgramsDao, private val gate: PermissionGate, private val config: ConfigStore) {
    suspend fun availability(programId: String): ProgramAvailability {
        val program = dao.program(programId) ?: return ProgramAvailability.PAUSED
        val required = dao.grants(programId).filter { it.required }.map { gate.state(PermissionManager.grant(it)) }
        return ProgramAvailability.of(UserState.of(program.userState), required, config.missingRequired(programId), sourceDisconnected = false)
    }

    fun stateOf(programId: String, capability: String): PermissionState? =
        gate.snapshot.grant(programId, capability)?.let(gate::state)
}
