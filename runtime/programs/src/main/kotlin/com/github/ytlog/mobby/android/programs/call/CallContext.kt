package com.github.ytlog.mobby.android.programs.call

import com.github.ytlog.mobby.android.programs.capability.Surface

/** Limits of a dry run: which device plugins the chat run allowed, and where storage goes instead. */
data class DryRunScope(val allowedDeviceActions: Map<String, Set<String>>)

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
