package com.github.ytlog.mobby.android.interaction.domain.gateway

import com.github.ytlog.mobby.android.interaction.domain.AgentId
import com.github.ytlog.mobby.android.interaction.domain.Conversation
import com.github.ytlog.mobby.android.interaction.domain.ConversationSummary
import com.github.ytlog.mobby.android.interaction.domain.NextTurnConfig

/** Resolves gateway and model choices without holding a second, mutable "current model". */
object ConversationGatewayResolver {
    fun preferred(profiles: List<GatewayProfile>, selected: GatewayDefault?): GatewayProfile? =
        selected?.let { choice -> profiles.firstOrNull { it.id == choice.id && it.agent == choice.agent && it.model.isNotBlank() } }
            ?: profiles.firstOrNull { it.agent == AgentId.PI && it.model.isNotBlank() }
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

    /** Use the selected gateway and the last model it still offers, or its default model. */
    fun newConversation(
        agent: AgentId, current: Conversation?, conversations: List<ConversationSummary>,
        profiles: List<GatewayProfile>, defaultGateway: GatewayDefault?,
    ): NextTurnConfig? {
        val recent = (listOfNotNull(current) + conversations.map { it.conversation })
            .filter { !it.deleted && it.config.agent == agent }
            .maxByOrNull { it.updatedAt }
            ?.config
        val selectedProfile = defaultGateway?.takeIf { it.agent == agent }?.let { choice ->
            profiles.firstOrNull { it.agent == agent && it.id == choice.id && it.model.isNotBlank() }
        }
        val recentProfile = profiles.firstOrNull { it.agent == agent && it.id == recent?.gatewayProfile && it.model.isNotBlank() }
        val gateway = selectedProfile ?: recentProfile ?: profiles.firstOrNull { it.agent == agent && it.model.isNotBlank() } ?: return null
        val model = recent?.model?.takeIf(gateway::offers) ?: gateway.model
        val reasoning = recent?.reasoning?.takeIf { recent.gatewayProfile == gateway.id && model == recent.model }
        return NextTurnConfig(agent, model, reasoning, "default", gateway.id, gateway.version)
    }
}
