package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.localization.CatalogIds

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.ui.gateway.gatewayLabel
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayProfile
import com.github.ytlog.mobby.android.conversation.domain.gateway.GatewayDefault
import com.github.ytlog.mobby.android.conversation.domain.gateway.ConversationGatewayResolver
import kotlinx.coroutines.*

@Composable internal fun TextEditDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value) }) { Text(AppStrings.save) } }, dismissButton = { TextButton(onClick = dismiss) { Text(AppStrings.cancel) } })
}
@Composable internal fun AgentConfigMenu(expanded: Boolean, dismiss: () -> Unit, c: Conversation, vm: ConversationViewModel, anchor: IntRect = IntRect.Zero) {
    if (!expanded) return
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by rememberSaveable(c.id.value) { mutableStateOf(c.config.agent) }
    var gatewayId by rememberSaveable(c.id.value) { mutableStateOf(c.config.gatewayProfile) }
    var model by rememberSaveable(c.id.value) { mutableStateOf(c.config.model) }
    var reasoning by rememberSaveable(c.id.value) { mutableStateOf(c.config.reasoning) }
    val option = agents.firstOrNull { it.agent == agent }
    val levels = option?.models?.get(model).orEmpty()
    FrostedMenu(true, dismiss, anchor) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            MenuSection(AppStrings.agentLabel) {
                AgentId.values().forEach { value -> MenuOption(value.label(), agent == value, icon = value.glyph()) {
                    agent = value
                    val gateway = profiles.firstOrNull { it.agent == value && it.id == gatewayId } ?: profiles.firstOrNull { it.agent == value }
                    gatewayId = gateway?.id.orEmpty(); model = gateway?.model.orEmpty(); reasoning = null
                } }
            }
            MenuSection(AppStrings.gateway) {
                profiles.filter { it.agent == agent }.forEach { gateway ->
                    MenuOption(gatewayLabel(gateway), gatewayId == gateway.id) { gatewayId = gateway.id; model = gateway.model; reasoning = null }
                }
                if (profiles.none { it.agent == agent }) MenuCaption(AppStrings.noGatewayAvailableOpenGatewaySettings)
            }
            MenuSection(AppStrings.model) {
                val gateway = profiles.firstOrNull { it.agent == agent && it.id == gatewayId }
                if (gateway?.models.isNullOrEmpty()) MenuCaption(AppStrings.noModelsConfiguredOpenGatewaySettings)
                val names = option?.modelNames.orEmpty()
                gateway?.models?.forEach { item -> MenuOption(modelMenuLabel(item.id, names), model == item.id) { model = item.id; reasoning = null } }
                val known = gateway?.models?.map { it.id }.orEmpty().toSet()
                if (known.isNotEmpty() && model.isNotBlank() && model !in known) MenuCaption(AppStrings.currentModelIsNotInTheSavedListSelect)
            }
            MenuSection(AppStrings.reasoningEffort) {
                if (levels.isEmpty()) MenuCaption(AppStrings.thisModelDoesNotOfferAdjustment)
                else {
                    MenuOption(AppStrings.default, reasoning == null) { reasoning = null }
                    levels.forEach { level -> MenuOption(level, reasoning == level) { reasoning = level } }
                }
            }
            MenuCaption(AppStrings.changesApplyToTheNextTurnTheCurrentExecution)
            if (agent != c.config.agent && c.hasTurns)
                MenuCaption(AppStrings.continueInThisConversationCodexClaudeCodeAndOpencode)
            if (agent != c.config.agent && c.draft.capabilities.any { !it.startsWith("plugin:") })
                MenuCaption(AppStrings.selectedSkillsBelongToTheAgentSelectThemAgain)
        }
        Button(
            onClick = {
                val p = profiles.firstOrNull { it.agent == agent && it.id == gatewayId }
                if (p != null) vm.enqueue {
                    val config = NextTurnConfig(agent, model, reasoning, c.config.workspace, p.id, p.version)
                    vm.actions.configure(c.id, config)
                    vm.rememberAgentSelection(config)
                }
                dismiss()
            },
            enabled = profiles.any { it.agent == agent && it.id == gatewayId },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(22.dp),
            colors = filledButtonColors(if (darkChrome()) MobbyColors.Dark.button else menuAccent(), if (darkChrome()) MobbyColors.Dark.onButton else MobbyColors.onAccent),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        ) { Text(AppStrings.apply, fontWeight = FontWeight.SemiBold) }
    }
}
@Composable internal fun ConfigDialog(vm: ConversationViewModel, c: Conversation?, onDismiss: () -> Unit, onApply: (NextTurnConfig, String?) -> Unit, anchor: IntRect = IntRect.Zero) {
    val state by vm.state.collectAsStateWithLifecycle()
    var project by rememberSaveable { mutableStateOf(c?.project) }
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    val defaultGateway by vm.defaultGateway.collectAsStateWithLifecycle()
    var agent by rememberSaveable { mutableStateOf(ConversationGatewayResolver.preferred(profiles, defaultGateway)?.agent ?: AgentId.PI) }
    var agentChosen by rememberSaveable { mutableStateOf(false) }
    val remembered = ConversationGatewayResolver.newConversation(agent, c, state.conversations, profiles, defaultGateway)
    val canCreate = remembered != null && (project == null || state.projects.any { it.name == project })
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    LaunchedEffect(defaultGateway, profiles, state.conversations) {
        if (!agentChosen) {
            agent = ConversationGatewayResolver.preferred(profiles, defaultGateway)?.agent ?: AgentId.PI
        }
        if (profiles.isNotEmpty() && profiles.none { it.agent == agent }) {
            agent = defaultGateway?.agent?.takeIf { candidate -> profiles.any { it.agent == candidate } } ?: profiles.first().agent
        }
    }
    FrostedMenu(true, onDismiss, anchor) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text(AppStrings.newConversation2, Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = menuInk(), fontWeight = FontWeight.Medium)
            MenuSection(AppStrings.project) {
                MenuOption(AppStrings.noProject, project == null) { project = null }
                state.projects.forEach { item -> MenuOption(item.name, project == item.name) { project = item.name } }
                if (state.projects.isEmpty()) MenuCaption(AppStrings.createProjectsInProjectManagementInTheConversationDrawer)
            }
            MenuSection(AppStrings.agentLabel) {
                AgentId.values().forEach { value ->
                    MenuOption(value.label(), agent == value, icon = value.glyph(), enabled = profiles.any { it.agent == value }) { agent = value; agentChosen = true }
                }
                if (profiles.none { it.agent == agent }) MenuCaption(AppStrings.noGatewayAvailableOpenGatewaySettings)
            }
        }
        Button(
            onClick = {
                val config = remembered ?: return@Button
                vm.enqueue { vm.rememberAgentSelection(config) }
                onApply(config, project)
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(22.dp),
            colors = filledButtonColors(if (darkChrome()) MobbyColors.Dark.button else menuAccent(), if (darkChrome()) MobbyColors.Dark.onButton else MobbyColors.onAccent),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        ) { Text(AppStrings.create, fontWeight = FontWeight.SemiBold) }
    }
}
@Composable internal fun PageHeader(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
        ActionIcon(AppStrings.back, back, AppIcons.Back)
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 48.dp), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}
@Composable internal fun SettingsPage(
    system: SystemStatus, appearance: Appearance, setAppearance: (Appearance) -> Unit, navigate: (String) -> Unit, back: () -> Unit, vm: ConversationViewModel,
    petEnabled: Boolean = false, petPermitted: Boolean = false, setPet: (Boolean) -> Unit = {},
) {
    val languageContext = LocalContext.current
    var languageError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.settings, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                SettingsItem(AppStrings.gatewaySettings, { navigate("gateway") }, AppStrings.configuredGatewaysAndModels)
                GroupDivider()
                SettingsItem(AppStrings.storageRetention, { navigate("history-limits") }, AppStrings.conversationAndAttachmentRetention)
                GroupDivider()
                SettingsItem(AppStrings.appUpdates, { navigate("updates") }, AppStrings.updateSettingSummary)
            }
            SettingsGroup(AppStrings.runtime) {
                SettingsItem(AppStrings.shellDiagnostics, { navigate("diagnostic") })
                GroupDivider()
                SettingsAction(AppStrings.recheckRuntime) { vm.enqueue { vm.report(vm.actions.initialize()) } }
            }
            SettingsCaption(if (system.ready) AppStrings.runtimeReady else system.message)
            SettingsGroup(AppStrings.languageTitle) {
                com.github.ytlog.mobby.android.localization.AppLanguage.values().forEachIndexed { index, language ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(language.nativeName, LanguagePreferences.current == language, {
                        languageError = !LanguagePreferences.select(languageContext, language)
                    })
                }
            }
            if (languageError) SettingsCaption(AppStrings.languageSaveFailed, error = true)
            SettingsGroup(AppStrings.appearance) {
                listOf(Appearance.SYSTEM to AppStrings.systemDefault, Appearance.DARK to AppStrings.dark, Appearance.LIGHT to AppStrings.light).forEachIndexed { index, (key, label) ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(label, appearance == key, { setAppearance(key) })
                }
            }
            SettingsGroup {
                SettingsToggle(AppStrings.floatingTaskBubble, petEnabled, setPet)
            }
            SettingsCaption(AppStrings.showAFloatingBubbleWhenYouLeaveTheApp)
            if (petEnabled && !petPermitted) SettingsCaption(AppStrings.permissionToDisplayOverOtherAppsIsRequired, error = true)
            SettingsGroup {
                SettingsItem(AppStrings.archivedRecentlyDeleted, { navigate("archived") })
                GroupDivider()
                SettingsItem(AppStrings.openSourceLicenses, { navigate("licenses") })
            }
        }
    }
}
internal fun modelMenuLabel(id: String, names: Map<String, String>): String {
    val name = names[id]?.takeIf { it.isNotBlank() && it != id } ?: return id
    return if (names.values.count { it == name } > 1) id else name
}

@Composable internal fun DiagnosticPage(vm: ConversationViewModel, back: () -> Unit) {
    val state by vm.diagnostic.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.shellDiagnostics, back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.status(state.phase.label()))
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = cardColor()) {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                    if (state.lines.isEmpty()) item { EmptyPlaceholder(AppStrings.noOutputYet, AppStrings.enterACommandAndTapRun) }
                    items(state.lines.size) { index -> androidx.compose.foundation.text.selection.SelectionContainer { Text(state.lines[index], fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            SettingsGroup { SettingsField(command, { command = it }, AppStrings.enterAShellCommand, singleLine = false, maxLines = 5) }
            SettingsGroup {
                SettingsAction(AppStrings.run, enabled = command.isNotBlank()) { val captured = command; vm.enqueue { vm.report(vm.actions.shell(captured)) } }
                GroupDivider()
                SettingsAction(AppStrings.stop) { vm.enqueue { vm.report(vm.actions.stopShell()) } }
            }
        }
    }
}
@Composable internal fun ArchivedPage(state: ConversationState, vm: ConversationViewModel, back: () -> Unit) {
    val rows = state.conversations.filter { it.conversation.archived || it.conversation.deleted }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.archivedRecentlyDeleted, back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (rows.isEmpty()) item { EmptyPlaceholder(AppStrings.noArchivedOrDeletedConversations, AppStrings.archivedOrDeletedConversationsAppearHere) }
            else item {
                SettingsGroup {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(row.conversation.config.agent.glyph(), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(12.dp))
                            Text(row.conversation.title, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { vm.enqueue { vm.report(if (row.conversation.deleted) vm.actions.delete(row.conversation.id, false) else vm.actions.archive(row.conversation.id, false)) } },
                                colors = textButtonColors(onButtonColor())) { Text(AppStrings.restore, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}
private val pluginCatalogIds = listOf(CatalogIds.PHONE, CatalogIds.COMMUNICATION, CatalogIds.FILES)
private fun pluginCatalogTabs(tablet: Boolean) = listOf(if (tablet) AppStrings.tablet else AppStrings.phone, AppStrings.communication, AppStrings.files)

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PluginPage(
    vm: ConversationViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val conversation = state.selected?.conversation
    val catalogue by vm.plugins.collectAsStateWithLifecycle()
    val error by vm.pluginsError.collectAsStateWithLifecycle()
    val loading by vm.pluginsLoading.collectAsStateWithLifecycle()
    val tablet = isTabletDisplay()
    val tabs = pluginCatalogTabs(tablet)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var rationale by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.loadPlugins() }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.enqueue {
            vm.report(vm.actions.saveDeviceDirectory(uri.toString()))
            vm.loadPlugins()
        }
    }
    fun requestAccess(plugin: Plugin, grant: Boolean = false) {
        val target = if (grant) plugin.grant else null
        when {
            grant && target == null -> Unit
            !grant && plugin.access == PluginAccess.ACCESSIBILITY -> context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            !grant && plugin.access == PluginAccess.DOCUMENT_TREE -> tree.launch(null)
            else -> {
                val needed = if (grant) target?.permissions.orEmpty() else plugin.permissions
                if (needed.isNotEmpty()) rationale = plugin.description to needed
            }
        }
    }
    DisposableEffect(lifecycle, LanguagePreferences.current) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.loadPlugins() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.plugins, onBack)
        rationale?.let { (explanation, needed) ->
            AlertDialog(onDismissRequest = { rationale = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(if (tablet) AppStrings.allowThisPluginToUseTabletCapabilities else AppStrings.allowThisPluginToUseDeviceCapabilities) }, text = { Text(explanation) },
                confirmButton = { TextButton(onClick = { val request = needed; rationale = null; permissions.launch(request.toTypedArray()) }) { Text(AppStrings.`continue`) } },
                dismissButton = { TextButton(onClick = { rationale = null }) { Text(AppStrings.cancel) } })
        }
        CatalogTabs(tabs, pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(it) } }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::loadPlugins) { Text(AppStrings.retry) } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pluginCatalogIds[it] }) { page ->
            val tab = pluginCatalogIds[page]
            val visible = catalogue.filter { it.category == tab }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty() && !loading && error == null) item {
                    EmptyPlaceholder(
                        if (catalogue.isEmpty()) AppStrings.noCallablePluginsAvailable else AppStrings.noPluginsInThisCategory,
                        if (catalogue.isEmpty()) AppStrings.pluginsProvidedByTheAppAppearHereWhenReady else AppStrings.availablePluginsAreInOtherCategories,
                    )
                }
                items(visible, key = { it.ref }) { plugin ->
                    val chosen = conversation?.draft?.capabilities?.contains(plugin.ref) == true
                    val grant = plugin.grant
                    val grantChosen = grant != null && conversation?.draft?.capabilities?.contains(grant.ref) == true
                    val swatch = catalogSwatch(plugin.ref)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CatalogRow(
                            title = plugin.name,
                            subtitle = plugin.description,
                            icon = if (plugin.access == PluginAccess.ACCESSIBILITY) AppIcons.Phone else AppIcons.Plugin,
                            iconForeground = swatch.first,
                            iconBackground = swatch.second,
                            action = when {
                                chosen -> AppStrings.remove
                                !plugin.available -> AppStrings.enable
                                else -> AppStrings.use
                            },
                            actionEnabled = !plugin.available || conversation != null,
                            onAction = {
                                if (chosen && conversation != null) vm.enqueue { vm.report(vm.actions.setPlugin(conversation.id, plugin, false)) }
                                else if (!plugin.available) requestAccess(plugin)
                                else if (conversation != null) vm.enqueue { vm.report(vm.actions.setPlugin(conversation.id, plugin, true)) }
                            },
                        )
                        plugin.grant?.let { grant ->
                            if (chosen || !grant.available) TextButton(onClick = {
                                if (grantChosen && conversation != null) vm.enqueue { vm.report(vm.actions.setPluginGrant(conversation.id, plugin, false)) }
                                else if (!grant.available) requestAccess(plugin, grant = true)
                                else if (conversation != null) vm.enqueue { vm.report(vm.actions.setPluginGrant(conversation.id, plugin, true)) }
                            }, enabled = grantChosen || grant.available || grant.permissions.isNotEmpty()) { Text(when {
                                grantChosen -> AppStrings.disable(grant.label)
                                !grant.available -> AppStrings.enable2(grant.label)
                                else -> AppStrings.allow(grant.label)
                            }) }
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun FindDialog(detail: ConversationDetail, onDismiss: () -> Unit, onSelect: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(detail, query) { ConversationSearch.find(detail, query) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.findInChat) }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text(AppStrings.findMessages) }, singleLine = true)
            if (query.isBlank()) EmptyPlaceholder(AppStrings.enterASearchTerm, AppStrings.onlyMessagesInThisConversationAreSearched)
            else if (results.isEmpty()) EmptyPlaceholder(AppStrings.noMatchingMessages, AppStrings.tryAnotherSearchTerm)
            else Text(AppStrings.matchingMessagesTapToLocate(results.size))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.targetKey }) { hit ->
                    TextButton(onClick = { onSelect(hit) }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (hit.messageId == null) AppStrings.you else detail.conversation.config.agent.label(), style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppStrings.close) } })
}
@Composable internal fun ShareDialog(detail: ConversationDetail, share: (String) -> Unit, onDismiss: () -> Unit) {
    val messages = detail.turns.flatMap { turn -> listOf("user:${turn.id.value}" to turn.userText) + turn.messages.map { "${turn.id.value}:${it.id}" to it.text } }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.selectMessagesToShare) }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { Text(AppStrings.runtimeLogsAndConfigurationAreExcludedByDefault, style = MaterialTheme.typography.bodySmall) }
            items(messages, key = { it.first }) { (id, text) ->
                ChoiceRow(text.take(200), id in selected, { selected = if (id in selected) selected - id else selected + id })
            }
        }
    }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { share(messages.filter { it.first in selected }.joinToString("\n\n") { it.second }); onDismiss() }) { Text(AppStrings.shareViaSystem) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(AppStrings.cancel) } })
}

@Composable internal fun HistoryDialog(id: ConversationId, vm: ConversationViewModel, dismiss: () -> Unit, content: @Composable (ConversationDetail) -> Unit) {
    var result by remember(id) { mutableStateOf<DataResult<ConversationDetail>?>(null) }
    LaunchedEffect(id) {
        result = try { DataResult.Loaded(vm.actions.history(id)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { DataResult.Failed(AppStrings.couldNotReadTheFullConversationCloseAndRetry) }
    }
    when (val value = result) {
        is DataResult.Loaded -> content(value.value)
        else -> AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(AppStrings.readConversationHistory) }, text = {
            if (value is DataResult.Failed) Text(value.message) else CircularProgressIndicator()
        }, confirmButton = { TextButton(onClick = dismiss) { Text(AppStrings.close) } })
    }
}
