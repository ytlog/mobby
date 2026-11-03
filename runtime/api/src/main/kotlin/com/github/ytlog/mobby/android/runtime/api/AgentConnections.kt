package com.github.ytlog.mobby.android.runtime.api

import com.github.ytlog.mobby.android.runtime.api.gateway.GatewayModelSummary

enum class AgentConnectionMode { AUTO, NATIVE_ONLY, API_ONLY }
data class AgentConnectionSetting(val agent: AgentId, val mode: AgentConnectionMode = AgentConnectionMode.AUTO, val gatewayId: String? = null)
/** No credentials, endpoints, or raw CLI output cross this boundary. */
data class NativeAccountSummary(val agent: AgentId, val provider: String, val label: String, val version: Long,
    val models: List<GatewayModelSummary>, val expired: Boolean = false)
data class NativeLoginMethod(val id: String, val label: String)
data class NativeLoginUpdate(val url: String? = null, val code: String? = null, val prompt: String? = null,
    val options: List<NativeLoginMethod> = emptyList(), val instructions: String? = null) {
    override fun toString() = "NativeLoginUpdate([transient])"
}
object NativeConnectionRef {
    fun id(agent: AgentId) = "native:${agent.name}"
    fun agent(id: String): AgentId? = AgentId.values().firstOrNull { id == this.id(it) }
}
