package com.mobby.runtime.api

import kotlinx.coroutines.flow.StateFlow

enum class EnvironmentPhase { INITIALIZING, READY, FAILED }
data class EnvironmentSnapshot(val phase: EnvironmentPhase, val summary: String, val error: RuntimeError? = null)
enum class GatewayProtocol { CHAT, RESPONSES, MESSAGES }
data class GatewayProfileSummary(
    val ref: GatewayProfileRef, val agent: AgentId, val endpoint: String, val model: String,
    val protocol: GatewayProtocol, val hasCredential: Boolean
)
/** Short-lived memory only; never include in a DTO toString, journal, Flow, or SavedState. */
class SecretInput(value: CharArray) {
    private val chars = value.copyOf()
    fun consume(): CharArray = chars.copyOf().also { chars.fill('\u0000') }
    override fun toString() = "[redacted]"
}
class SaveGatewayRequest(val agent: AgentId, val endpoint: String, val model: String,
    val protocol: GatewayProtocol, val credential: SecretInput? = null) {
    override fun toString() = "SaveGatewayRequest(agent=$agent)"
}
sealed interface AdminResult<out T> {
    data class Success<T>(val value: T) : AdminResult<T>
    data class Failed(val error: RuntimeError) : AdminResult<Nothing>
}
interface RuntimeAdminClient {
    val environment: StateFlow<EnvironmentSnapshot>
    suspend fun initialize(): AdminResult<Unit>
    suspend fun listGatewayProfiles(): AdminResult<List<GatewayProfileSummary>>
    suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary>
}

/** Internal diagnostics, deliberately separate from the product Agent enum and conversation API. */
data class DiagnosticState(val phase: RunPhase? = null, val output: List<String> = emptyList(), val error: RuntimeError? = null)
interface RuntimeDiagnosticsClient {
    val state: StateFlow<DiagnosticState>
    suspend fun executeShell(command: String): CommandResult
    suspend fun stopShell(): CommandResult
}
