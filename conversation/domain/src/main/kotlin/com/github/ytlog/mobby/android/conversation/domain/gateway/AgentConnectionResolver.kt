package com.github.ytlog.mobby.android.conversation.domain.gateway

import com.github.ytlog.mobby.android.conversation.domain.AgentId

enum class ConnectionSource { NATIVE, API }
enum class ConnectionMode { AUTO, NATIVE_ONLY, API_ONLY }
data class AgentConnectionPreference(val agent: AgentId, val mode: ConnectionMode = ConnectionMode.AUTO, val gatewayId: String? = null)
data class AgentLoginMethod(val id: String, val label: String)
/** Short-lived UI data; authorization URLs and codes never enter saved conversation state. */
data class AgentLoginUpdate(val url: String? = null, val code: String? = null, val prompt: String? = null,
    val options: List<AgentLoginMethod> = emptyList(), val instructions: String? = null) {
    override fun toString() = "AgentLoginUpdate([transient])"
}
data class AgentLogin(val agent: AgentId, val provider: String, val label: String, val version: Long,
    val models: List<GatewayModel>, val expired: Boolean = false, val reasoningByModel: Map<String, Set<String>> = emptyMap()) {
    fun route() = GatewayProfile(agent, "native:${agent.name}", version, "", models.firstOrNull()?.id.orEmpty(),
        if (agent == AgentId.CLAUDE_CODE) "MESSAGES" else "RESPONSES", true, models,
        source = ConnectionSource.NATIVE, connectionLabel = label, nativeReasoning = reasoningByModel)
}

/** Resolve before admission only. An accepted turn keeps its route, even when execution fails. */
object AgentConnectionResolver {
    fun available(api: List<GatewayProfile>, native: List<GatewayProfile>, preferences: List<AgentConnectionPreference>): List<GatewayProfile> =
        AgentId.values().flatMap { agent ->
            val choice = preferences.firstOrNull { it.agent == agent } ?: AgentConnectionPreference(agent)
            val login = native.filter { it.agent == agent && it.model.isNotBlank() }
            val gateways = api.filter { it.agent == agent && it.model.isNotBlank() }
                .let { values -> choice.gatewayId?.let { id -> values.filter { it.id == id } } ?: values }
            when (choice.mode) {
                ConnectionMode.NATIVE_ONLY -> login
                ConnectionMode.API_ONLY -> gateways
                ConnectionMode.AUTO -> login.ifEmpty { gateways }
            }
        }
}
