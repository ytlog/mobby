package com.github.ytlog.mobby.android.interaction.domain.gateway

import com.github.ytlog.mobby.android.interaction.domain.AgentId
import com.github.ytlog.mobby.android.interaction.domain.Conversation
import com.github.ytlog.mobby.android.interaction.domain.ConversationSummary
import com.github.ytlog.mobby.android.interaction.domain.NextTurnConfig

/** Resolves gateway and model choices without holding a second, mutable "current model". */
object ConversationGatewayResolver {
    private fun GatewayProfile.offers(modelId: String): Boolean = modelId.isNotBlank() &&
        (modelId == model || models.any { it.id == modelId })

    fun preferred(profiles: List<GatewayProfile>, selected: GatewayDefault?): GatewayProfile? =
        selected?.let { choice -> profiles.firstOrNull { it.id == choice.id && it.agent == choice.agent && it.model.isNotBlank() } }
            ?: profiles.firstOrNull { it.model.isNotBlank() }

    /** Preserve a usable conversation choice; repair only missing, stale, or invalid references. */
    fun repair(config: NextTurnConfig, profiles: List<GatewayProfile>, selected: GatewayDefault?): NextTurnConfig? {
        val same = profiles.firstOrNull { it.id == config.gatewayProfile && it.agent == config.agent && it.model.isNotBlank() }
        val target = same ?: preferred(profiles, selected) ?: return null
        val model = config.model.takeIf { same != null && target.offers(it) } ?: target.model
        val updated = config.copy(agent = target.agent, model = model,
            reasoning = config.reasoning.takeIf { target.agent == config.agent && model == config.model },
            gatewayProfile = target.id, gatewayVersion = target.version)
        return updated.takeIf { it != config }
    }

    /** New conversations use the selected gateway's default model; another Agent can reuse its last valid choice. */
    fun newConversation(
        agent: AgentId, current: Conversation?, conversations: List<ConversationSummary>,
        profiles: List<GatewayProfile>, defaultGateway: GatewayDefault?,
    ): NextTurnConfig? {
        val preferred = preferred(profiles, defaultGateway)
        if (defaultGateway != null && preferred?.id == defaultGateway.id && preferred.agent == agent && defaultGateway.agent == agent)
            return NextTurnConfig(agent, preferred.model, null, "default", preferred.id, preferred.version)
        val recent = (listOfNotNull(current) + conversations.map { it.conversation })
            .filter { !it.deleted && it.config.agent == agent }
            .maxByOrNull { it.updatedAt }
            ?.config
        val lastProfile = profiles.firstOrNull { it.agent == agent && it.id == recent?.gatewayProfile && it.model.isNotBlank() }
        if (recent != null && lastProfile != null) return recent.copy(
            model = recent.model.takeIf { lastProfile.offers(it) } ?: lastProfile.model,
            gatewayVersion = lastProfile.version,
            reasoning = recent.reasoning.takeIf { lastProfile.offers(recent.model) })
        val gateway = profiles.firstOrNull { it.agent == agent && it.model.isNotBlank() }
            ?: return null
        return NextTurnConfig(agent, gateway.model, null, "default", gateway.id, gateway.version)
    }
}
