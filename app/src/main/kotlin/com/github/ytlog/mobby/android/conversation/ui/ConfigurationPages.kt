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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.domain.gateway.ConnectionSource
import com.github.ytlog.mobby.android.conversation.domain.gateway.ConversationGatewayResolver
import kotlinx.coroutines.*

@Composable internal fun TextEditDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value) }) { Text(AppStrings.save) } }, dismissButton = { TextButton(onClick = dismiss) { Text(AppStrings.cancel) } })
}
@Composable internal fun AgentConfigMenu(expanded: Boolean, dismiss: () -> Unit, c: Conversation, vm: ConversationViewModel, anchor: IntRect = IntRect.Zero, manageConnections: () -> Unit = {}) {
    if (!expanded) return
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.connections.collectAsStateWithLifecycle()
    var selectedAgent by remember(c.id) { mutableStateOf<AgentId?>(null) }
    val drafts = remember(c.id) { mutableStateMapOf<AgentId, NextTurnConfig>() }
    var showSettings by remember(c.id) { mutableStateOf(false) }
    val agent = selectedAgent ?: c.config.agent
    var field by remember(c.id, agent, showSettings) { mutableStateOf<AgentSetting?>(null) }
    val availableAgents = AgentId.entries.filter { value ->
        profiles.any { it.agent == value } && agents.none { it.agent == value && it.unavailable != null }
    }
    val connections = profiles.filter { it.agent == agent }
    val draft = drafts[agent] ?: if (agent == c.config.agent) c.config else connections.firstOrNull()?.let {
        NextTurnConfig(agent, it.model, null, c.config.workspace, it.id, it.version)
    }
    val profile = connections.firstOrNull { it.id == draft?.gatewayProfile }
    val canChangeConnection = connections.isNotEmpty() && (connections.size > 1 || profile == null)
    val model = draft?.model.orEmpty()
    val levels = profile?.nativeReasoning?.get(model).orEmpty()
    val reasoning = draft?.reasoning?.takeIf { it in levels }
    val names = profile?.models.orEmpty().associate { it.id to it.name }
    val modelLabel = modelMenuLabel(model, names).ifBlank { AppStrings.unavailable }
    val config = profile?.let { route ->
        draft?.takeIf { route.offers(model) && agent in availableAgents }
            ?.copy(reasoning = reasoning, workspace = c.config.workspace, gatewayVersion = route.version)
    }
    val changed = config != null && config != c.config
    val openConnections = { dismiss(); manageConnections() }
    FrostedMenu(true, dismiss, anchor, maxWidth = 304.dp, dimBehind = true) {
        val ink = menuInk()
        val secondaryInk = ink.copy(alpha = 0.68f)
        val accent = menuAccent()
        Column(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(agent.label(), Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, color = ink)
                Text(if (changed) AppStrings.pendingApply else AppStrings.nextTurn, Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodySmall, color = secondaryInk)
            }
            if (availableAgents.isNotEmpty()) AgentIconRail(availableAgents, agent) { selectedAgent = it }
            if (connections.isEmpty()) AgentMenuNote(AppStrings.connectAgentFirst)
            if (agent != c.config.agent && c.hasTurns)
                AgentMenuNote(AppStrings.continueInThisConversationCodexClaudeCodeAndOpencode)
            if (agent != c.config.agent && c.draft.capabilities.any { !it.startsWith("plugin:") })
                AgentMenuNote(AppStrings.selectedSkillsBelongToTheAgentSelectThemAgain)
            if (showSettings) {
                HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = ink.copy(alpha = 0.10f))
                AgentSettingsToggle(expanded = true, model = modelLabel, effort = null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                ) { showSettings = false }
                if (canChangeConnection || profile == null) {
                    AgentSettingRow(
                        AppStrings.connectionSource, profile?.let(::connectionLabel) ?: AppStrings.noAvailableConnection,
                        expanded = field == AgentSetting.CONNECTION, expandable = canChangeConnection,
                    ) { field = AgentSetting.CONNECTION.takeUnless { field == it } }
                }
                if (field == AgentSetting.CONNECTION) {
                    connections.forEach { route ->
                        AgentSettingChoice(connectionLabel(route), profile?.id == route.id) {
                            if (profile?.id != route.id) drafts[agent] =
                                NextTurnConfig(agent, route.model, null, c.config.workspace, route.id, route.version)
                            field = null
                        }
                    }
                }
                if (profile != null && draft != null) {
                    AgentModelPicker(modelLabel, source = if (canChangeConnection) null else connectionLabel(profile),
                        expanded = field == AgentSetting.MODEL, expandable = profile.models.isNotEmpty(),
                    ) { field = AgentSetting.MODEL.takeUnless { field == it } }
                    when {
                        profile.models.isEmpty() -> AgentMenuNote(
                            if (profile.source == ConnectionSource.NATIVE) AppStrings.noNativeModels
                            else AppStrings.noModelsConfiguredOpenGatewaySettings
                        )
                        field == AgentSetting.MODEL -> profile.models.forEach { item ->
                            AgentSettingChoice(modelMenuLabel(item.id, names), model == item.id) {
                                if (model != item.id) drafts[agent] = draft.copy(model = item.id, reasoning = null)
                                field = null
                            }
                        }
                    }
                    if (names.isNotEmpty() && model.isNotBlank() && model !in names)
                        AgentMenuNote(AppStrings.currentModelIsNotInTheSavedListSelect)
                    if (levels.isEmpty()) {
                        AgentSettingRow(AppStrings.reasoningEffort, AppStrings.thisModelDoesNotOfferAdjustment,
                            expanded = false, expandable = false, onClick = {})
                    } else {
                        AgentReasoningChoices(levels, reasoning) { drafts[agent] = draft.copy(reasoning = it) }
                    }
                }
            }
            if (showSettings || connections.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = openConnections)
                        .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppIcon(AppIcons.Settings, null, Modifier.size(16.dp), tint = secondaryInk)
                    Text(AppStrings.manageAgentConnections, style = MaterialTheme.typography.bodySmall, color = ink)
                }
            }
        }
        HorizontalDivider(color = ink.copy(alpha = 0.10f))
        Row(
            Modifier.fillMaxWidth().background(ink.copy(alpha = 0.035f)).padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!showSettings) AgentSettingsToggle(
                expanded = false, model = modelLabel,
                effort = if (levels.isEmpty()) null else reasoning?.let(AppStrings::reasoningLevelLabel) ?: AppStrings.default,
                modifier = Modifier.weight(1f),
            ) { showSettings = true }
            Button(
                onClick = {
                    val applied = config ?: return@Button
                    vm.enqueue {
                        vm.actions.configure(c.id, applied)
                        vm.rememberAgentSelection(applied)
                    }
                    dismiss()
                },
                enabled = changed,
                modifier = Modifier.widthIn(min = 56.dp).heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = MobbyColors.onAccent,
                    disabledContainerColor = ink.copy(alpha = 0.06f), disabledContentColor = secondaryInk,
                ),
            ) { Text(AppStrings.apply, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
        }
    }
}

private enum class AgentSetting { CONNECTION, MODEL }

@Composable
private fun AgentSettingsToggle(expanded: Boolean, model: String, effort: String?, modifier: Modifier, onClick: () -> Unit) {
    val ink = menuInk()
    val secondary = ink.copy(alpha = 0.68f)
    val state = if (expanded) AppStrings.expanded else AppStrings.collapsed
    Row(
        modifier.clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = state }
            .heightIn(min = 44.dp).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(AppStrings.modelAndReasoning,
                style = if (expanded) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = if (expanded) ink else secondary)
            if (!expanded) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model, Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = ink)
                if (effort != null) Text(effort, style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
        AppIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, Modifier.size(14.dp), tint = secondary)
    }
}

@Composable
private fun AgentMenuNote(text: String) {
    Text(text, Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = menuInk().copy(alpha = 0.68f))
}

@Composable
private fun AgentSettingRow(title: String, value: String, expanded: Boolean, expandable: Boolean, onClick: () -> Unit) {
    val ink = menuInk()
    val secondary = ink.copy(alpha = 0.68f)
    val state = if (expanded) AppStrings.expanded else AppStrings.collapsed
    val action = if (!expandable) Modifier else Modifier
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { stateDescription = state }
    Row(
        Modifier.fillMaxWidth().then(action).heightIn(min = if (expandable) 44.dp else 32.dp)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = secondary)
        Text(value, Modifier.weight(1f).alignByBaseline(), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = ink)
        if (expandable)
            AppIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown, null, Modifier.size(14.dp), tint = secondary)
    }
}

@Composable
private fun AgentModelPicker(value: String, source: String?, expanded: Boolean, expandable: Boolean, onClick: () -> Unit) {
    val ink = menuInk()
    val secondary = ink.copy(alpha = 0.68f)
    val state = if (expanded) AppStrings.expanded else AppStrings.collapsed
    val action = if (!expandable) Modifier else Modifier
        .clickable(role = Role.Button, onClick = onClick).semantics { stateDescription = state }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).then(action),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(AppStrings.model, Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = secondary)
            if (source != null) Text(source, Modifier.weight(1f).alignByBaseline(),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        Row(
            Modifier.fillMaxWidth().border(1.dp, ink.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = ink)
            if (expandable) AppIcon(if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                null, Modifier.size(14.dp), tint = secondary)
        }
    }
}

@Composable
private fun AgentSettingChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) menuAccent() else menuInk()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = color)
        if (selected) AppIcon(AppIcons.Check, null, Modifier.size(16.dp), tint = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentReasoningChoices(levels: Set<String>, selected: String?, select: (String?) -> Unit) {
    val ink = menuInk()
    val accent = menuAccent()
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(AppStrings.reasoningEffort, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.68f))
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (listOf(null) + levels).forEach { level ->
                val active = level == selected
                Box(
                    Modifier.widthIn(min = 44.dp).heightIn(min = 44.dp).clip(shape)
                        .selectable(selected = active, role = Role.RadioButton) { select(level) }
                        .padding(horizontal = 3.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val background = if (active) Modifier.background(accent.copy(alpha = 0.12f), shape) else Modifier
                    Text(level?.let(AppStrings::reasoningLevelLabel) ?: AppStrings.default,
                        background.padding(horizontal = 7.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.bodySmall, color = if (active) accent else ink)
                }
            }
        }
    }
}

@Composable
private fun AgentIconRail(agents: List<AgentId>, selected: AgentId, select: (AgentId) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)
    val ink = menuInk()
    val accent = menuAccent()
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(shape),
    ) {
        val overflows = maxWidth < 48.dp * agents.size
        val showArrows = overflows && maxWidth >= 144.dp
        val slot = if (overflows) 48.dp else maxWidth / agents.size
        val slotPixels = with(LocalDensity.current) { slot.roundToPx() }
        LaunchedEffect(agents, selected, slotPixels, scroll.viewportSize) {
            val index = agents.indexOf(selected)
            if (overflows && index >= 0 && scroll.viewportSize >= slotPixels) {
                val start = index * slotPixels
                val target = scroll.value.coerceIn((start + slotPixels - scroll.viewportSize).coerceAtLeast(0), start)
                if (scroll.value != target) scroll.scrollTo(target)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showArrows) ActionIcon(AppStrings.previousAgentOptions,
                { scope.launch { scroll.animateScrollTo((scroll.value - scroll.viewportSize).coerceAtLeast(0)) } },
                AppIcons.Back, enabled = scroll.canScrollBackward, tint = ink)
            Row(Modifier.weight(1f).horizontalScroll(scroll).selectableGroup()) {
                agents.forEach { agent ->
                    val active = agent == selected
                    Box(
                        Modifier.width(slot).height(44.dp).clip(shape)
                            .selectable(selected = active, role = Role.RadioButton) { select(agent) }
                            .semantics { contentDescription = agent.label() },
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(agent.glyph(), null, Modifier.size(18.dp), tint = if (active) accent else ink)
                        if (active) Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                                .size(10.dp, 2.dp).background(accent, RoundedCornerShape(1.dp)),
                        )
                    }
                }
            }
            if (showArrows) ActionIcon(AppStrings.nextAgentOptions,
                { scope.launch { scroll.animateScrollTo((scroll.value + scroll.viewportSize).coerceAtMost(scroll.maxValue)) } },
                AppIcons.ChevronRight, enabled = scroll.canScrollForward, tint = ink)
        }
    }
}
@Composable internal fun ConfigDialog(vm: ConversationViewModel, c: Conversation?, onDismiss: () -> Unit, onApply: (NextTurnConfig, String?) -> Unit, anchor: IntRect = IntRect.Zero, manageConnections: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var project by rememberSaveable { mutableStateOf(c?.project) }
    val profiles by vm.connections.collectAsStateWithLifecycle()
    val defaultGateway by vm.defaultGateway.collectAsStateWithLifecycle()
    var agent by rememberSaveable { mutableStateOf(ConversationGatewayResolver.preferred(profiles, defaultGateway)?.agent ?: AgentId.PI) }
    var agentChosen by rememberSaveable { mutableStateOf(false) }
    val remembered = ConversationGatewayResolver.newConversation(agent, c, state.conversations, profiles, defaultGateway)
    val canCreate = remembered != null && (project == null || state.projects.any { it.name == project })
    // The ViewModel loads gateways when the runtime becomes ready. Re-reading the
    // runtime on every menu opening delays creation behind its serial UI queue.
    LaunchedEffect(Unit) { if (!vm.gatewaysLoaded.value) vm.enqueue { vm.refresh() } }
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
                AgentId.values().filter { value -> profiles.any { it.agent == value } }.forEach { value ->
                    MenuOption(value.label(), agent == value, icon = value.glyph()) { agent = value; agentChosen = true }
                }
                if (profiles.none { it.agent == agent }) MenuCaption(AppStrings.connectAgentFirst)
                MenuOption(AppStrings.manageAgentConnections, false) { onDismiss(); manageConnections() }
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
    val context = LocalContext.current
    var languageError by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.settings, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                SettingsItem(AppStrings.setupGuide, { navigate("setup") }, AppStrings.setupGuideSummary)
                GroupDivider()
                SettingsItem(AppStrings.agentConnectionsTitle, { navigate("connections") }, AppStrings.agentConnectionsSummary)
                GroupDivider()
                SettingsItem(AppStrings.storageRetention, { navigate("history-limits") }, AppStrings.conversationAndAttachmentRetention)
                GroupDivider()
                SettingsItem(AppStrings.appUpdates, { navigate("updates") }, AppStrings.updateSettingSummary)
            }
            SettingsGroup(AppStrings.runtime) {
                SettingsItem(AppStrings.screenModeTitle, {
                    context.startActivity(Intent(context, ScreenControlSettingsActivity::class.java))
                })
                GroupDivider()
                SettingsItem(AppStrings.shellDiagnostics, { navigate("diagnostic") })
                GroupDivider()
                SettingsAction(AppStrings.recheckRuntime) { vm.enqueue { vm.report(vm.actions.initialize()) } }
            }
            SettingsCaption(if (system.ready) AppStrings.runtimeReady else system.message)
            SettingsGroup(AppStrings.languageTitle) {
                com.github.ytlog.mobby.android.localization.AppLanguage.values().forEachIndexed { index, language ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(language.nativeName, LanguagePreferences.current == language, {
                        languageError = !LanguagePreferences.select(context, language)
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
    val selectPlugin = rememberDirectPluginSelector(vm, conversation)
    val screen = ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value
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
            !grant && plugin.access == PluginAccess.ACCESSIBILITY -> context.startActivity(
                if (plugin.ref == "plugin:device:screen") screenAccessSettingsIntent(context)
                else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            AlertDialog(onDismissRequest = { rationale = null }, title = { Text(if (tablet) AppStrings.allowThisPluginToUseTabletCapabilities else AppStrings.allowThisPluginToUseDeviceCapabilities) }, text = { Text(explanation) },
                confirmButton = { TextButton(onClick = { val request = needed; rationale = null; permissions.launch(request.toTypedArray()) }) { Text(AppStrings.`continue`) } },
                dismissButton = { TextButton(onClick = { rationale = null }) { Text(AppStrings.cancel) } })
        }
        CatalogTabs(tabs, pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(it) } }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::loadPlugins) { Text(AppStrings.retry) } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pluginCatalogIds[it] }) { page ->
            val tab = pluginCatalogIds[page]
            // Program development has its own entry in the add sheet.
            val visible = catalogue.filter { it.category == tab && it.ref != PROGRAM_DEVELOPMENT }
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
                                else if (plugin.ref == "plugin:device:screen" && shouldRecommendAdb(screen)) {
                                    if (conversation != null) selectPlugin(plugin.ref) else context.startActivity(screenSetupIntent(context))
                                }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppStrings.findInChat) }, text = {
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppStrings.selectMessagesToShare) }, text = {
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
        else -> AlertDialog(onDismissRequest = dismiss, title = { Text(AppStrings.readConversationHistory) }, text = {
            if (value is DataResult.Failed) Text(value.message) else CircularProgressIndicator()
        }, confirmButton = { TextButton(onClick = dismiss) { Text(AppStrings.close) } })
    }
}
