package com.github.ytlog.mobby.android.runtime.api.gateway

import com.github.ytlog.mobby.android.runtime.api.AgentId
import com.github.ytlog.mobby.android.runtime.api.GatewayProfileRef

enum class GatewayProtocol { RESPONSES, MESSAGES }
data class GatewayModelSummary(val id: String, val name: String)
data class GatewayProfileSummary(
    val ref: GatewayProfileRef, val agent: AgentId, val endpoint: String, val model: String,
    val protocol: GatewayProtocol, val hasCredential: Boolean,
    val models: List<GatewayModelSummary> = emptyList(), val catalogError: String? = null,
)
/** Short-lived memory only; never include in a DTO toString, journal, Flow, or SavedState. */
class SecretInput(value: CharArray) {
    private val chars = value.copyOf()
    fun consume(): CharArray = chars.copyOf().also { chars.fill('\u0000') }
    override fun toString() = "[redacted]"
}
data class GatewaySelection(val agent: AgentId, val profile: GatewayProfileRef)
data class GatewayCandidateAddresses(val responses: String, val messages: String = "")
class SaveGatewayRequest(val id: String?, val addresses: GatewayCandidateAddresses, val model: String,
    val credential: SecretInput? = null, val selectedModels: Set<String> = emptySet()) {
    override fun toString() = "SaveGatewayRequest(id=$id, credentials=[redacted])"
}
class InspectGatewayRequest(val id: String?, val addresses: GatewayCandidateAddresses, val model: String = "",
    val credential: SecretInput? = null) {
    override fun toString() = "InspectGatewayRequest(id=$id, credentials=[redacted])"
}
data class GatewayInspectionSummary(val model: String, val models: List<GatewayModelSummary>,
    val supportedAgents: Set<AgentId>, val catalogError: String?)
enum class GatewayCheckOutcome { SUCCEEDED, HTTP_ERROR, INCOMPLETE_RESPONSE, INVALID_RESPONSE, RESPONSE_TOO_LARGE, DNS_ERROR, TLS_ERROR, TIMEOUT, CONNECTION_ERROR }
/** Contains no response body, endpoint or credential. A small protocol request is not a CLI acceptance test. */
data class GatewayCheck(val profile: GatewayProfileRef, val outcome: GatewayCheckOutcome, val httpStatus: Int? = null)
