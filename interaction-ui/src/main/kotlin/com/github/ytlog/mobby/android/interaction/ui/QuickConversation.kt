package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.domain.gateway.ConversationGatewayResolver
import com.github.ytlog.mobby.android.localization.FloatingStrings

/** Small entry adapters; draft ordering, submission and streaming stay in the existing view model. */
internal fun ConversationViewModel.openQuickConversation(id: ConversationId?, ready: (ConversationId) -> Unit = {}) = enqueue {
    if (id != null) {
        actions.select(id)
        ready(id)
    } else {
        val profiles = actions.gateways()
        val preferred = actions.defaultGateway()
        val agent = ConversationGatewayResolver.preferred(profiles, preferred)?.agent ?: AgentId.PI
        val config = ConversationGatewayResolver.newConversation(agent, state.value.selected?.conversation,
            state.value.conversations, profiles, preferred)
        if (config == null) report(OperationResult.Failed(UiStrings.noGatewayAvailableOpenGatewaySettings))
        else ready(actions.create(config))
    }
}

internal fun ConversationViewModel.readScreen() {
    if (screenReading.value) return
    screenReading.value = true
    enqueue {
        var submitted = false
        try {
            val original = composer.value
            val id = original.conversation ?: return@enqueue
            if (!enableQuickPlugin(id, "plugin:device:screen")) return@enqueue
            // A late permission/catalogue result must never send a different conversation or changed draft.
            if (composer.value != original) {
                report(OperationResult.Failed(FloatingStrings.draftChanged))
                return@enqueue
            }
            val text = original.value.text.ifBlank { FloatingStrings.screenPrompt }
            edit(TextFieldValue(text))
            send()
            submitted = true
            enqueue { screenReading.value = false }
        } finally { if (!submitted) screenReading.value = false }
    }
}

/** Overlay hosts select through the same catalogue/permission use case without Activity launchers. */
internal fun ConversationViewModel.selectQuickPlugin(id: ConversationId, ref: String) = enqueue {
    enableQuickPlugin(id, ref)
}

private suspend fun ConversationViewModel.enableQuickPlugin(id: ConversationId, ref: String): Boolean {
    val plugin = when (val result = actions.plugins()) {
        is DataResult.Failed -> { report(OperationResult.Failed(result.message)); return false }
        is DataResult.Loaded -> result.value.firstOrNull { it.ref == ref }
    }
    if (plugin == null) { report(OperationResult.Failed(UiStrings.unknownPlugin)); return false }
    return when (val enabled = actions.setPlugin(id, plugin, true)) {
        is OperationResult.Failed -> { report(enabled); false }
        OperationResult.Done -> true
    }
}
