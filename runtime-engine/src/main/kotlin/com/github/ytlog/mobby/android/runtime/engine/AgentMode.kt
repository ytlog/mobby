package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.AgentId

/** Internal launcher identities; product API exposes only AgentId. */
enum class AgentMode(val label: String) { SHELL("Shell"), CLAUDE("Claude Code"), CODEX("Codex"), OPEN_CODE("OpenCode") }

/** CLI session ids. OpenCode uses an underscore prefix such as ses_… */
val AgentSessionId = Regex("[A-Za-z0-9_-]{1,100}")

fun AgentId.launchMode(): AgentMode = when (this) {
    AgentId.CODEX -> AgentMode.CODEX
    AgentId.CLAUDE_CODE -> AgentMode.CLAUDE
    AgentId.OPEN_CODE -> AgentMode.OPEN_CODE
}

fun AgentMode.productAgent(): AgentId = when (this) {
    AgentMode.CODEX -> AgentId.CODEX
    AgentMode.CLAUDE -> AgentId.CLAUDE_CODE
    AgentMode.OPEN_CODE -> AgentId.OPEN_CODE
    AgentMode.SHELL -> throw IllegalArgumentException("Shell is not a product agent")
}

fun AgentMode.program(): String = when (this) {
    AgentMode.SHELL -> "bash"
    AgentMode.CLAUDE -> "claude"
    AgentMode.CODEX -> "codex"
    AgentMode.OPEN_CODE -> "opencode"
}
