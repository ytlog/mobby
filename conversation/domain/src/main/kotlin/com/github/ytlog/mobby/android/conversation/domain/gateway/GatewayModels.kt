package com.github.ytlog.mobby.android.conversation.domain.gateway

import com.github.ytlog.mobby.android.conversation.domain.AgentId

data class GatewayModel(val id: String, val name: String)
data class GatewayAddresses(val responses: String, val messages: String = "") {
    fun forAgent(agent: AgentId): String = if (agent == AgentId.CLAUDE_CODE) messages.ifBlank { responses } else responses
}
data class GatewayProfile(val agent: AgentId, val id: String, val version: Long, val endpoint: String, val model: String, val protocol: String, val hasCredential: Boolean, val models: List<GatewayModel> = emptyList(), val catalogError: String? = null, val temporary: Boolean = false) {
    fun offers(modelId: String): Boolean = modelId.isNotBlank() && (modelId == model || models.any { it.id == modelId })
}
data class GatewayDefault(val agent: AgentId, val id: String, val version: Long)
data class GatewayCheckReport(val passed: Boolean, val message: String)
data class GatewayCatalogResult(val models: List<GatewayModel>, val catalogError: String?)
sealed interface GatewaySaveResult {
    data class Saved(val models: List<GatewayModel>, val catalogError: String?, val agents: Set<AgentId> = emptySet()) : GatewaySaveResult
    data class Failed(val message: String) : GatewaySaveResult
}
class GatewayEdit(val id: String?, val addresses: GatewayAddresses, val model: String,
    val credential: CharArray?, val selectedModels: Set<String> = emptySet()) {
    override fun toString() = "GatewayEdit(id=$id, credentials=[redacted])"
}
