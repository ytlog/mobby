package com.github.ytlog.mobby.android.runtime.engine

/** Internal launcher identities; product API exposes only AgentId. */
enum class AgentMode(val label: String) { SHELL("Shell"), CLAUDE("Claude Code"), CODEX("Codex") }
