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
import kotlinx.coroutines.launch
import com.github.ytlog.mobby.android.conversation.domain.AgentId
import com.github.ytlog.mobby.android.conversation.domain.gateway.AgentLogin
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal enum class SetupStep { AGENTS, SIGN_IN, SCREEN, DONE }

/** Only guide progress; existing accounts, routes, screen choices and chats are never written here. */
internal class SetupProgress(context: Context) {
    private val preferences = context.getSharedPreferences("mobby.setup-guide", Context.MODE_PRIVATE)
    fun agent(): AgentId? = preferences.getString("agent", AgentId.CODEX.name)?.takeUnless { it == "API" }?.let(AgentId::valueOf)
    fun selectAgent(agent: AgentId?) { preferences.edit().putString("agent", agent?.name ?: "API").apply() }
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

@Composable internal fun SetupGuidePage(vm: ConversationViewModel, step: SetupStep, selectedAgent: AgentId?,
    selectAgent: (AgentId?) -> Unit, api: () -> Unit, next: () -> Unit, previous: () -> Unit, finish: () -> Unit) {
    val accounts by vm.nativeAccounts.collectAsStateWithLifecycle()
    val system by vm.status.collectAsStateWithLifecycle()
    val routes by vm.connections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var completing by remember { mutableStateOf(false) }
    if (step == SetupStep.SCREEN) {
        val controller = ScreenUi.controller
        if (controller != null) ScreenControlSettings(controller, onBack = previous, onFinished = finish, embedded = true, stepOffset = 2)
        else SetupWizardFrame(AppStrings.setupGuide, 3, AppStrings.wizardChooseMode, AppStrings.setupRuntimePreparing,
            previous, AppStrings.wizardNext, {}, enabled = false,
            steps = setupAgentStepLabels() + screenSetupPath(com.github.ytlog.mobby.android.runtime.api.device.ScreenMode.CURRENT).map(::screenStepLabel))
        return
    }
    val account = accounts.firstOrNull { it.agent == selectedAgent && !it.expired }
    val choosing = step == SetupStep.AGENTS
    val ready = account != null || selectedAgent == null && routes.any { it.source == com.github.ytlog.mobby.android.conversation.domain.gateway.ConnectionSource.API }
    SetupWizardFrame(AppStrings.setupGuide, if (choosing) 1 else 2,
        if (choosing) AppStrings.wizardChooseAgent else selectedAgent?.let { AppStrings.wizardSignIn(it.label()) } ?: AppStrings.wizardApiTitle,
        if (choosing) AppStrings.wizardChooseAgentHelp else if (selectedAgent == null) AppStrings.wizardApiHelp else account?.let { "${AppStrings.agentSignedIn} · ${it.label}" } ?: AppStrings.wizardSignInHelp,
        back = previous,
        primaryText = if (choosing || ready) AppStrings.wizardNext else selectedAgent?.let { AppStrings.wizardSignIn(it.label()) } ?: AppStrings.configureApiGateway,
        primary = { if (choosing || ready) next() else if (selectedAgent == null) api() else showLogin = true },
        enabled = choosing || selectedAgent == null || system.ready,
        secondaryText = if (choosing) AppStrings.later else AppStrings.wizardPrevious, secondary = previous,
        busy = completing, error = error,
        steps = setupAgentStepLabels() + screenSetupPath(ScreenUi.controller?.state?.value?.mode
            ?: com.github.ytlog.mobby.android.runtime.api.device.ScreenMode.CURRENT).map(::screenStepLabel)) {
        if (choosing) SettingsGroup {
            listOf(AgentId.CODEX, AgentId.CLAUDE_CODE, AgentId.PI, AgentId.OPEN_CODE).forEachIndexed { index, agent ->
                if (index > 0) GroupDivider()
                ChoiceRow(agent.label(), selectedAgent == agent, { selectAgent(agent) })
            }
            GroupDivider()
            ChoiceRow(AppStrings.wizardUseApi, selectedAgent == null, { selectAgent(null) })
        }
        else {
            if (!system.ready && selectedAgent != null) Text(AppStrings.setupRuntimePreparing, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = next) { Text(AppStrings.wizardSkipSignIn) }
        }
    }
    if (showLogin && selectedAgent != null) NativeLoginDialog(vm, selectedAgent, { showLogin = false }, {
        showLogin = false
        completing = true
        scope.launch {
            try { vm.completeNativeSignIn(selectedAgent); next() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = AppStrings.nativeLoginFailed }
            finally { completing = false }
        }
    })
}
