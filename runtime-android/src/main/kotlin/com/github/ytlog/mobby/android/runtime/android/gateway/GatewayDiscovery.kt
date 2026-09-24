package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.runtime.api.gateway.*

import com.github.ytlog.mobby.android.runtime.api.gateway.GatewayCheckOutcome
import com.github.ytlog.mobby.android.runtime.api.GatewayProfileRef
import com.github.ytlog.mobby.android.runtime.engine.AgentMode

/** A protocol is supported only after an actual native request completes successfully. */
internal data class GatewayInspection(
    val model: String,
    val models: List<GatewayModel>,
    val catalogError: String?,
    val supported: Map<AgentMode, String>,
    val outcomes: Map<GatewayProtocol, GatewayCheckOutcome>,
)

internal class GatewayDiscovery(
    private val catalog: suspend (GatewayConfig) -> CatalogResult = { GatewayCatalog().fetch(it) },
    private val probe: suspend (GatewayConfig) -> GatewayCheckOutcome = {
        GatewayProbe().check(GatewayProfileRef("discovery", 0), it).outcome
    },
) {
    suspend fun inspect(addresses: GatewayCandidateAddresses, preferredModel: String, key: String): GatewayInspection {
        require(preferredModel.length <= 200 && preferredModel.none { it.isISOControl() })
        val candidates = mapOf(AgentMode.CODEX to GatewayEndpoint.base(addresses.responses),
            AgentMode.OPEN_CODE to GatewayEndpoint.base(addresses.responses),
            AgentMode.CLAUDE to GatewayEndpoint.base(addresses.messages.ifBlank { addresses.responses }))
        val configs = candidates.mapValues { (mode, endpoint) -> GatewayConfig(endpoint, preferredModel.ifBlank { "catalog-probe" }, key, mode.gatewayProtocol()).also { it.validateFor(mode) } }
        val catalogResults = configs.values.distinctBy { it.endpoint to it.protocol }.map { catalog(it) }
        val models = catalogResults.filterIsInstance<CatalogResult.Ready>().flatMap { it.models }.distinctBy { it.id }.take(2_000)
        val selectedModel = preferredModel.ifBlank { models.firstOrNull()?.id.orEmpty() }
        val supported = linkedMapOf<AgentMode, String>()
        val outcomes = linkedMapOf<GatewayProtocol, GatewayCheckOutcome>()
        if (selectedModel.isNotEmpty()) {
            val checked = mutableMapOf<Pair<String, GatewayProtocol>, GatewayCheckOutcome>()
            configs.forEach { (mode, config) ->
                val outcome = checked.getOrPut(config.endpoint to config.protocol) { probe(config.copy(model = selectedModel)) }
                outcomes[config.protocol] = outcome
                if (outcome == GatewayCheckOutcome.SUCCEEDED) supported[mode] = config.endpoint
            }
        }
        val error = if (models.isEmpty()) (catalogResults.firstOrNull() as? CatalogResult.Unavailable)?.message ?: "模型列表为空" else null
        return GatewayInspection(selectedModel, models, error, supported, outcomes)
    }
}

/** Keep the verified default, catalog picks and explicitly entered model IDs together. */
internal fun selectedCatalog(defaultModel: String, fetched: List<GatewayModel>, selected: Set<String>): List<GatewayModel> {
    require((selected + defaultModel).size <= 2_000 && (selected + defaultModel).all { id ->
        id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }
    }) { "模型列表无效" }
    val byId = fetched.associateBy { it.id }
    val default = byId[defaultModel] ?: GatewayModel(defaultModel, defaultModel)
    return listOf(default) + fetched.filter { it.id in selected && it.id != defaultModel } +
        selected.filter { it != defaultModel && it !in byId }.map { GatewayModel(it, it) }
}
