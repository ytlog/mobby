package com.github.ytlog.mobby.android.device

import kotlinx.serialization.json.JsonObject

/**
 * Development commands for chat runs, implemented by the program platform and installed by the app.
 * device-plugins stays below the platform: it only knows this port.
 */
interface ProgramDevelopmentPort {
    /** Capability catalog, SDK guide and package rules generated from what is implemented. */
    fun catalog(): String
    fun validate(path: String): JsonObject
    /** [allowedDeviceActions] are the device actions this chat run may use; only those queries run for real. */
    fun dryRun(path: String, events: String, timeoutMillis: Long, allowedDeviceActions: Map<String, Set<String>>): JsonObject
    fun records(programId: String, limit: Int): JsonObject
}

object ProgramDevelopment {
    @Volatile var port: ProgramDevelopmentPort? = null
}
