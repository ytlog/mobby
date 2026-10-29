package com.github.ytlog.mobby.android.graph

import android.content.Context
import com.github.ytlog.mobby.android.programs.base.ai.*
import com.github.ytlog.mobby.android.programs.capability.Effect
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayTextModels
import com.github.ytlog.mobby.android.runtime.android.gateway.TextModelFailure
import com.github.ytlog.mobby.android.runtime.android.gateway.TextTurn

/** Program `ai.call` through the gateways the user configured; their keys stay in this process. */
internal class AppModelGateway(context: Context) : ModelGatewayPort {
    private val models = GatewayTextModels(context)

    override fun gateways() = models.gateways().map { ModelGateway(it.id, it.label, it.models) }
    override fun preferred() = models.preferred()

    override suspend fun complete(gatewayId: String, model: String, request: AiRequest): AiReply = try {
        models.complete(gatewayId, model, request.system, request.messages.map { TextTurn(it.role, it.text) }, request.maxOutputTokens)
            .let { AiReply(it.text, it.inputTokens, it.outputTokens) }
    } catch (failure: TextModelFailure) {
        throw ModelCallException(failure.reason, if (failure.reached) Effect.UNKNOWN else Effect.NONE)
    }
}
