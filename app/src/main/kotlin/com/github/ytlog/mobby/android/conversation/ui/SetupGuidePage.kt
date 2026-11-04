package com.github.ytlog.mobby.android.conversation.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.gateway.AgentLogin
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal enum class SetupStep { AGENTS, SCREEN, DONE }

/** Only guide progress; existing accounts, routes, screen choices and chats are never written here. */
internal class SetupProgress(context: Context) {
    private val preferences = context.getSharedPreferences("mobby.setup-guide", Context.MODE_PRIVATE)
    fun read(): SetupStep = if (preferences.getBoolean("seen", false)) SetupStep.DONE
        else preferences.getString("step", null)?.let { SetupStep.valueOf(it) } ?: SetupStep.AGENTS
    fun save(step: SetupStep) {
        preferences.edit().putBoolean("seen", step == SetupStep.DONE).apply {
            if (step == SetupStep.DONE) remove("step") else putString("step", step.name)
        }.apply()
    }
}

@Composable internal fun AgentSetupChoices(accounts: List<AgentLogin>, enabled: Boolean = true,
    signIn: (AgentId) -> Unit, api: () -> Unit) {
    SettingsGroup(AppStrings.setupLoginRecommended) {
        listOf(AgentId.CODEX, AgentId.CLAUDE_CODE, AgentId.PI, AgentId.OPEN_CODE).forEachIndexed { index, agent ->
            if (index > 0) GroupDivider()
            val account = accounts.firstOrNull { it.agent == agent }
            val detail = when {
                account?.expired == true -> AppStrings.nativeLoginExpired
                account != null -> "${AppStrings.agentSignedIn} · ${account.label}"
                agent == AgentId.CODEX -> AppStrings.setupCodexAccount
                agent == AgentId.CLAUDE_CODE -> AppStrings.setupClaudeAccount
                agent == AgentId.PI -> AppStrings.setupPiAccount
                else -> AppStrings.setupOpenCodeAccount
            }
            SettingsItem(agent.label(), { signIn(agent) }, detail, enabled = enabled)
        }
    }
    SettingsGroup(AppStrings.setupApiAlternative) { SettingsItem(AppStrings.configureApiGateway, api) }
}

@Composable internal fun SetupGuidePage(vm: ConversationViewModel, step: SetupStep, signIn: (AgentId) -> Unit,
    api: () -> Unit, next: () -> Unit, agents: () -> Unit, screen: () -> Unit, finish: () -> Unit) {
    val accounts by vm.nativeAccounts.collectAsStateWithLifecycle()
    val routes by vm.connections.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val screenState = ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.setupGuide, if (step == SetupStep.SCREEN) agents else finish)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (step == SetupStep.AGENTS) "1 / 2" else "2 / 2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { if (step == SetupStep.AGENTS) 0.5f else 1f }, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Text(if (step == SetupStep.AGENTS) AppStrings.setupAgentTitle else AppStrings.setupScreenTitle,
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            SettingsCaption(if (step == SetupStep.AGENTS) AppStrings.setupAgentBody else AppStrings.setupScreenBody)
            if (step == SetupStep.AGENTS) {
                AgentSetupChoices(accounts, system.ready, signIn, api)
                if (!system.ready) SettingsCaption(AppStrings.setupRuntimePreparing)
                if (routes.any { it.source == com.github.ytlog.mobby.android.conversation.domain.gateway.ConnectionSource.API }) SettingsCaption(AppStrings.setupApiPreserved)
                Button(onClick = next, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                    Text(AppStrings.setupNextScreen)
                }
                TextButton(onClick = finish) { Text(AppStrings.later) }
            } else {
                SettingsGroup {
                    SettingsItem(AppStrings.setupScreenAction, screen,
                        screenState?.let { screenModeLabel(it.mode) + if (it.connected) " · ${AppStrings.screenBackendReady}" else "" })
                }
                SettingsCaption(AppStrings.currentScreenQuickHelp)
                SettingsCaption(AppStrings.virtualScreenModeHelp)
                Button(onClick = finish, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                    Text(AppStrings.setupStart)
                }
                TextButton(onClick = agents) { Text(AppStrings.setupAgentAgain) }
            }
        }
    }
}
