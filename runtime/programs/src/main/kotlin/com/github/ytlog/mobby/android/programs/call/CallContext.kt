package com.github.ytlog.mobby.android.programs.call

import com.github.ytlog.mobby.android.programs.capability.Surface

/**
 * Limits of a dry run: the device queries the chat run already allowed, and the permissions the
 * package requests, which stand in for grants. Everything with side effects is simulated.
 */
data class DryRunScope(val allowedQueries: Set<String>, val requested: Map<String, com.github.ytlog.mobby.android.programs.permission.Scope> = emptyMap())

/** Who is calling. Built from a run credential or a UI session, never from request bodies. */
data class CallContext(
    val programId: String,
    val version: String,
    val surface: Surface,
    val runId: String? = null,
    val uiSessionId: String? = null,
    val dryRun: DryRunScope? = null,
) {
    init { require((runId != null) != (uiSessionId != null)) { "A call comes from exactly one run or UI session" } }

    /** Request IDs are unique per caller. */
    val caller: String get() = runId?.let { "run:$it" } ?: "ui:$uiSessionId"
}
