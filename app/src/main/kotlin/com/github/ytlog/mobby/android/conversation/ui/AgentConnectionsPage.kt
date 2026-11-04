package com.github.ytlog.mobby.android.conversation.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.*
import com.github.ytlog.mobby.android.conversation.ui.gateway.gatewayLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal fun connectionLabel(profile: GatewayProfile): String = if (profile.source == ConnectionSource.NATIVE)
    "${AppStrings.nativeSignIn} · ${profile.connectionLabel}" else "API · ${gatewayLabel(profile)}"

@Composable internal fun AgentConnectionsPage(vm: ConversationViewModel, initialAgent: AgentId? = null,
    back: () -> Unit, gateway: () -> Unit, connected: () -> Unit = {}, loginDismissed: (() -> Unit)? = null) {
    val logins by vm.nativeAccounts.collectAsStateWithLifecycle()
    val settings by vm.connectionSettings.collectAsStateWithLifecycle()
    val routes by vm.connections.collectAsStateWithLifecycle()
    val gateways by vm.gateways.collectAsStateWithLifecycle()
    var selected by remember(initialAgent) { mutableStateOf(initialAgent) }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(initialAgent != null) }
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    fun save(setting: AgentConnectionPreference) { vm.enqueue {
        val result = vm.actions.saveConnectionSetting(setting)
        vm.report(result)
        if (result == OperationResult.Done) vm.refresh()
    } }
    Column(Modifier.fillMaxSize()) {
        PageHeader(selected?.label() ?: AppStrings.agentConnectionsTitle, { if (selected != null) { selected = null; showLogin = false } else back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val agent = selected
            if (agent == null) {
                SettingsCaption(AppStrings.connectAgentsBody)
                SettingsGroup {
                    listOf(AgentId.CODEX, AgentId.CLAUDE_CODE, AgentId.PI, AgentId.OPEN_CODE).forEachIndexed { index, value ->
                        if (index > 0) GroupDivider()
                        val login = logins.firstOrNull { it.agent == value }
                        val current = routes.firstOrNull { it.agent == value }
                        SettingsItem(value.label(), { selected = value }, current?.let(::connectionLabel)
                            ?: if (login?.expired == true) AppStrings.nativeLoginExpired else AppStrings.noAvailableConnection)
                    }
                }
                SettingsGroup { SettingsItem(AppStrings.configureApiGateway, gateway, AppStrings.configuredGatewaysAndModels) }
            } else {
                val login = logins.firstOrNull { it.agent == agent }
                val setting = settings.firstOrNull { it.agent == agent } ?: AgentConnectionPreference(agent)
                val current = routes.firstOrNull { it.agent == agent }
                SettingsGroup(AppStrings.nativeSignIn) {
                    SettingsItem(
                        if (login == null || login.expired) AppStrings.signInAgent else AppStrings.switchAgentAccount,
                        { showLogin = true },
                        if (login == null) AppStrings.agentSignedOut else if (login.expired) AppStrings.nativeLoginExpired else "${AppStrings.agentSignedIn} · ${login.label}",
                    )
                    if (login != null) {
                        GroupDivider()
                        SettingsAction(AppStrings.signOutAgent) { vm.enqueue {
                            vm.report(vm.actions.logoutAgent(agent)); vm.refresh()
                        } }
                    }
                }
                SettingsGroup(AppStrings.connectionModeTitle) {
                    listOf(ConnectionMode.AUTO to AppStrings.connectionAuto, ConnectionMode.NATIVE_ONLY to AppStrings.connectionNativeOnly,
                        ConnectionMode.API_ONLY to AppStrings.connectionApiOnly).forEachIndexed { index, (mode, title) ->
                        if (index > 0) GroupDivider()
                        ChoiceRow(title, setting.mode == mode, { save(setting.copy(mode = mode)) })
                    }
                }
                SettingsGroup(AppStrings.configureApiGateway) {
                    ChoiceRow(AppStrings.automaticApiGateway, setting.gatewayId == null, { save(setting.copy(gatewayId = null)) })
                    gateways.filter { it.agent == agent }.forEach { profile ->
                        GroupDivider()
                        ChoiceRow(gatewayLabel(profile), setting.gatewayId == profile.id, { save(setting.copy(gatewayId = profile.id)) })
                    }
                    GroupDivider(); SettingsItem(AppStrings.configureApiGateway, gateway)
                }
                SettingsCaption("${AppStrings.currentConnection}：${current?.let(::connectionLabel) ?: AppStrings.noAvailableConnection}")
                if (login?.expired == true && current?.source == ConnectionSource.API) SettingsCaption(AppStrings.nativeFallbackActive)
                SettingsCaption(AppStrings.failedNoSourceRetry)
                SettingsCaption(AppStrings.nativeModelAliases)
                notice?.let { SettingsCaption(it) }
            }
        }
    }
    if (showLogin && selected != null) NativeLoginDialog(vm, selected!!, { showLogin = false; loginDismissed?.invoke() }, {
        notice = AppStrings.nativeLoginSucceeded
        showLogin = false
        scope.launch {
            vm.refresh()
            vm.connections.value.firstOrNull { it.agent == selected }?.let { profile ->
                vm.rememberAgentSelection(NextTurnConfig(profile.agent, profile.model, null, "default", profile.id, profile.version))
                vm.state.value.selected?.conversation?.takeIf { !it.hasTurns && it.draft.text.isBlank() }?.let { current ->
                    vm.actions.configure(current.id, NextTurnConfig(profile.agent, profile.model, null, current.config.workspace, profile.id, profile.version))
                }
            }
            connected()
        }
    })
}

@Composable private fun NativeLoginDialog(vm: ConversationViewModel, agent: AgentId, dismiss: () -> Unit, success: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var methods by remember { mutableStateOf<List<AgentLoginMethod>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val updates = remember { MutableStateFlow(AgentLoginUpdate()) }
    val update by updates.collectAsStateWithLifecycle()
    var answers by remember { mutableStateOf(Channel<String>(Channel.BUFFERED)) }
    var answer by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); opened = url }
        catch (_: Exception) { error = AppStrings.openNativeLoginPage }
    }
    fun start(method: String) {
        if (job?.isActive == true) return
        error = null; updates.value = AgentLoginUpdate(); opened = null; answer = ""
        answers.close(); answers = Channel(Channel.BUFFERED)
        job = scope.launch {
            val result = vm.actions.loginAgent(agent, method, { next ->
                val previous = updates.value
                updates.value = next.copy(url = next.url ?: previous.url, code = next.code ?: previous.code, instructions = next.instructions ?: previous.instructions)
            }, answers.receiveAsFlow())
            if (result == OperationResult.Done) success()
            else { error = if (result is OperationResult.Failed) result.message else AppStrings.nativeLoginFailed; job = null }
        }
    }
    LaunchedEffect(agent) {
        when (val result = vm.actions.nativeLoginMethods(agent)) {
            is DataResult.Loaded -> { methods = result.value; if (result.value.size == 1) start(result.value.single().id) }
            is DataResult.Failed -> { methods = emptyList(); error = result.message }
        }
    }
    LaunchedEffect(update.url) { update.url?.takeIf { it != opened }?.let(::open) }
    DisposableEffect(Unit) { onDispose { job?.cancel(); answers.close(); updates.value = AgentLoginUpdate() } }
    AlertDialog(
        onDismissRequest = { job?.cancel(); dismiss() },
        modifier = Modifier.testTag("native-login-dialog"),
        containerColor = raisedColor(),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = { Text("${agent.label()} · ${AppStrings.signInAgent}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (job?.isActive == true || methods == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text(AppStrings.waitingNativeLogin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (job?.isActive != true) {
                    methods?.takeIf { it.isNotEmpty() }?.let { available ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column {
                                available.forEachIndexed { index, method ->
                                    if (index > 0) GroupDivider()
                                    SettingsItem(method.label, { start(method.id) })
                                }
                            }
                        }
                    }
                    if (methods?.isEmpty() == true) Text(AppStrings.nativeMethodsUnavailable, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                update.url?.let { url ->
                    Button(onClick = { open(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                        Text(AppStrings.openNativeLoginPage)
                    }
                }
                update.code?.let { code ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(AppStrings.nativeLoginCode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text(AppStrings.copyOriginalText) }
                        }
                    }
                }
                update.instructions?.takeIf { it != update.prompt }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                update.prompt?.let { prompt ->
                    Text(prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (update.options.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column {
                                update.options.forEachIndexed { index, option ->
                                    if (index > 0) GroupDivider()
                                    SettingsAction(option.label) { answers.trySend(option.id); updates.value = update.copy(prompt = null, options = emptyList()) }
                                }
                            }
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            SettingsField(answer, { answer = it }, AppStrings.nativeLoginAnswer, visualTransformation = PasswordVisualTransformation())
                        }
                        Button(onClick = { answers.trySend(answer); answer = ""; updates.value = update.copy(prompt = null) },
                            enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp)) {
                            Text(AppStrings.nativeLoginContinue)
                        }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { job?.cancel(); dismiss() }) { Text(AppStrings.cancel) } })
}
