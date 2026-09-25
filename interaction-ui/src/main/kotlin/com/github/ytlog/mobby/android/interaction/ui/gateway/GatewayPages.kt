package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.ui.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

internal fun gatewayBaseAddress(value: String): String = value.trim().trimEnd('/')
    .replace(Regex("(?i)/(?:responses|messages|chat/completions)$"), "")

@Composable internal fun GatewayPage(vm: ConversationViewModel, startAdding: Boolean = false,
                                     back: () -> Unit) {
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    val visibleProfiles = profiles.filterNot { it.temporary }
    val state by vm.state.collectAsStateWithLifecycle()
    val defaultGateway by vm.defaultGateway.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(if (startAdding) "new" else null) }
    var notice by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            vm.enqueue { vm.refresh() }
            withContext(Dispatchers.IO) { delay(5_000) }
        }
    }
    if (editing == null) GatewayList(visibleProfiles, notice, defaultGateway, state.selected?.conversation?.config, agents, back,
        select = vm::chooseGateway, open = { editing = it ?: "new"; notice = "" })
    else key(editing) {
        GatewayForm(visibleProfiles, vm::enqueue, vm.actions::saveGateway, vm.actions::inspectGateway, vm::refresh, { editing = null }, vm.actions::checkGateway,
            editingId = editing.takeUnless { it == "new" } ?: "", delete = vm.actions::deleteGateway,
            onSaved = { message ->
            notice = message
            editing = null
        })
    }
}

@Composable internal fun GatewayList(profiles: List<GatewayProfile>, notice: String, defaultGateway: GatewayDefault?, current: NextTurnConfig?,
    agents: List<AgentOption>, back: () -> Unit, select: (GatewayProfile, String, String?) -> Unit, open: (String?) -> Unit) {
    val configured = profiles.filter { it.endpoint.isNotBlank() }
    val selectedId = current?.gatewayProfile?.takeIf { id -> configured.any { it.id == id } }
        ?: defaultGateway?.id?.takeIf { id -> configured.any { it.id == id } }
    val selectedProfiles = configured.filter { it.id == selectedId }
    val selectedProfile = selectedProfiles.firstOrNull { it.agent == current?.agent }
        ?: selectedProfiles.firstOrNull { it.agent == defaultGateway?.agent }
        ?: selectedProfiles.firstOrNull()
    val selectedModel = current?.takeIf { it.gatewayProfile == selectedId }?.model ?: selectedProfile?.model.orEmpty()
    val selectedReasoning = current?.takeIf { it.gatewayProfile == selectedId && it.model == selectedModel }?.reasoning
    var expandedGatewayId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.gateway, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (configured.isEmpty()) EmptyPlaceholder(AppStrings.noGatewaysConfigured, AppStrings.addAGatewayToSelectItAndFetchModels)
            else SettingsGroup(AppStrings.configured) {
                configured.groupBy { it.id }.entries.forEachIndexed { index, entry ->
                    if (index > 0) GroupDivider()
                    val representative = entry.value.first()
                    val selected = selectedId == representative.id
                    val isCurrent = current?.gatewayProfile == representative.id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).selectable(selected = selected, role = Role.RadioButton) {
                            if (expandedGatewayId == representative.id) expandedGatewayId = null
                            else {
                                expandedGatewayId = representative.id
                                if (!selected) select(representative, representative.model, null)
                            }
                        }.semantics { contentDescription = AppStrings.selectGateway(representative.id) }
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Text(gatewaySummary(representative), style = MaterialTheme.typography.bodyLarge)
                            Text(entry.value.joinToString(" · ") { it.agent.label() }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val status = listOfNotNull(if (representative.temporary) AppStrings.temporaryLocalGateway else null,
                                if (isCurrent) AppStrings.currentConversation else null, if (defaultGateway?.id == representative.id) AppStrings.defaultForNewConversations else null).joinToString(" · ")
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (selected) AppIcon(AppIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        if (!representative.temporary) {
                            ActionIcon(AppStrings.editGateway, { open(entry.key) }, AppIcons.Edit, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (selected && expandedGatewayId == representative.id && selectedProfile != null) {
                        val models = (selectedProfiles.flatMap { it.models } + GatewayModel(selectedProfile.model, selectedProfile.model))
                            .filter { it.id.isNotBlank() }.distinctBy { it.id }
                        GroupDivider()
                        Text(AppStrings.model, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        models.forEach { item ->
                            ChoiceRow(if (item.name == item.id) item.id else "${item.name} · ${item.id}", selectedModel == item.id,
                                { select(selectedProfile, item.id, null) }, enabled = current != null)
                        }
                        val levels = agents.firstOrNull { it.agent == selectedProfile.agent }?.models?.get(selectedModel).orEmpty()
                        if (current != null && levels.isNotEmpty()) {
                            GroupDivider()
                            Text(AppStrings.reasoningEffort, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChoiceRow(AppStrings.default, selectedReasoning == null, { select(selectedProfile, selectedModel, null) })
                            levels.forEach { level -> ChoiceRow(level, selectedReasoning == level, { select(selectedProfile, selectedModel, level) }) }
                        }
                    }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice)
            SettingsGroup { SettingsAction(AppStrings.addGateway) { open(null) } }
            SettingsCaption(AppStrings.gatewaySelectionHelp)
        }
    }
}

internal fun gatewaySummary(profile: GatewayProfile): String {
    val providerId = GatewayProviders.match(profile.agent, profile.endpoint)
    val provider = (if (profile.temporary) AppStrings.localModelService else null)
        ?: GatewayProviders.find(providerId)?.label
        ?: runCatching { java.net.URI(profile.endpoint).host }.getOrNull()
        ?: AppStrings.custom
    val catalog = when {
        profile.catalogError != null -> AppStrings.modelListNotUpdated
        profile.models.isNotEmpty() -> AppStrings.models(profile.models.size)
        else -> null
    }
    return listOfNotNull(provider, profile.model.takeIf { it.isNotBlank() }, catalog).joinToString(" · ")
}

@Composable internal fun GatewayForm(profiles: List<GatewayProfile>, submit: (suspend () -> Unit) -> Unit,
    save: suspend (GatewayEdit) -> GatewaySaveResult,
    inspect: suspend (GatewayEdit) -> DataResult<GatewayInspectionResult>,
    refresh: suspend () -> Unit, back: () -> Unit,
    check: suspend (GatewayProfile) -> DataResult<GatewayCheckReport>,
    editingId: String? = profiles.singleOrNull()?.id,
    delete: suspend (String) -> OperationResult = { OperationResult.Failed(AppStrings.cannotDelete) },
    onSaved: ((String) -> Unit)? = null,
) {
    val group = profiles.filter { it.id == editingId }
    val saved = group.firstOrNull()
    var providerId by remember { mutableStateOf(GatewayProviders.CUSTOM) }
    var baseEndpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var keyEdited by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<GatewayInspectionResult?>(null) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manualModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelSearch by remember { mutableStateOf("") }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var providerAnchor by remember { mutableStateOf(IntRect.Zero) }
    var modelAnchor by remember { mutableStateOf(IntRect.Zero) }
    var addModelDialog by remember { mutableStateOf(false) }
    var newModelId by remember { mutableStateOf("") }
    var modelError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var noticeError by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf(false) }
    var connectionNotice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val checkScope = rememberCoroutineScope()
    LaunchedEffect(editingId) {
        val first = group.firstOrNull()
        providerId = first?.let {
            val matched = GatewayProviders.match(it.agent, it.endpoint)
            matched.takeIf { id -> GatewayProviders.find(id)?.candidates()?.let { candidates ->
                group.all { profile -> candidates.forAgent(profile.agent) == profile.endpoint }
            } == true } ?: GatewayProviders.CUSTOM
        } ?: GatewayProviders.CUSTOM
        baseEndpoint = gatewayBaseAddress(GatewayProviders.find(providerId)?.candidates()?.responses ?: first?.endpoint.orEmpty())
        model = first?.model.orEmpty()
        selectedModels = first?.models?.map { it.id }?.toSet().orEmpty()
        manualModels = first?.models?.map { it.id }?.toSet().orEmpty()
        key = ""; keyEdited = false; inspection = null
    }
    fun candidates(): GatewayAddresses = GatewayProviders.find(providerId)?.candidates()
        ?: gatewayBaseAddress(baseEndpoint).let { GatewayAddresses(it, it) }
    fun edit() = GatewayEdit(editingId?.takeIf { it.isNotBlank() }, candidates(), model.trim(),
        if (keyEdited) key.toCharArray() else null, selectedModels + listOfNotNull(model.takeIf { it.isNotBlank() }))
    fun changed() { noticeError = false; inspection = null; connectionNotice = ""; notice = "" }
    fun modelChanged(value: String) {
        model = value
        inspection = inspection?.copy(model = value, supportedAgents = emptySet())
        selectedModels = selectedModels + value
        noticeError = false; connectionNotice = ""; notice = AppStrings.probeAgainWithTheCurrentModelToConfirmAvailable
    }
    val inspected = inspection
    val savedMatches = saved != null && model.trim() == saved.model && !keyEdited &&
        group.all { candidates().forAgent(it.agent) == it.endpoint }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (!editingId.isNullOrBlank()) AppStrings.editGateway else AppStrings.addGateway, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.enterABaseAddressAndKeyThenProbeThe)
            SettingsGroup { Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                providerAnchor = IntRect(origin.x.roundToInt(), origin.y.roundToInt(),
                    origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                SettingsAction(AppStrings.serviceLabel(GatewayProviders.find(providerId)?.label ?: AppStrings.custom), enabled = !busy && !checking) { providerExpanded = true }
                FrostedMenu(providerExpanded, { providerExpanded = false }, providerAnchor) {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                        MenuSection(AppStrings.selectService) {
                        (GatewayProviders.all.map { it.id to it.label } +
                            (GatewayProviders.CUSTOM to AppStrings.custom)).forEach { (id, label) ->
                            MenuOption(label, id == providerId) {
                                    providerId = id
                                    GatewayProviders.find(id)?.let { baseEndpoint = gatewayBaseAddress(it.candidates().responses) }
                                    providerExpanded = false
                                    changed()
                                }
                        }
                        }
                    }
                }
            } }
            SettingsGroup {
                SettingsField(baseEndpoint, { baseEndpoint = it; changed() }, AppStrings.baseAddress, enabled = !busy && !checking && providerId == GatewayProviders.CUSTOM)
                GroupDivider()
                SettingsField(key, { key = it; keyEdited = true; changed() },
                    if (saved?.hasCredential == true && !keyEdited) AppStrings.keySavedEnterAReplacement else AppStrings.apiKeyOptionalWithoutAuthentication,
                    enabled = !busy && !checking, visualTransformation = PasswordVisualTransformation())
                if (saved?.hasCredential == true) {
                    GroupDivider()
                    SettingsAction(AppStrings.removeSavedKey, enabled = !busy && !checking) { key = ""; keyEdited = true; changed() }
                }
            }
            val supported = inspected?.supportedAgents ?: group.map { it.agent }.toSet()
            if (supported.isNotEmpty()) SettingsCaption(AppStrings.agents(if (inspected != null) AppStrings.confirmedLabel else AppStrings.savedLabel, supported.joinToString("、") { it.label() }))
            if (listOf(candidates().responses, candidates().messages).any { it.startsWith("http://", ignoreCase = true) })
                SettingsCaption(AppStrings.httpSendsKeysAndContentInPlainTextUse, error = true)
            SettingsGroup { SettingsAction(AppStrings.probeSupportedAgentsAndModels, enabled = !busy && !checking && candidates().responses.isNotBlank()) {
                if (providerId == GatewayProviders.CUSTOM) baseEndpoint = gatewayBaseAddress(baseEndpoint)
                noticeError = false; busy = true; notice = AppStrings.probingAvailableAgentsAndModels
                submit {
                    val request = edit()
                    try {
                        when (val result = inspect(request)) {
                            is DataResult.Loaded -> {
                                inspection = result.value
                                model = result.value.model
                                val available = result.value.models.map { it.id }.toSet()
                                manualModels = (manualModels + selectedModels) - available
                                selectedModels = selectedModels + model
                                noticeError = result.value.supportedAgents.isEmpty()
                                notice = if (result.value.supportedAgents.isEmpty()) AppStrings.noAvailableAgentConfirmedCheckAddressModelKeyAnd
                                    else AppStrings.confirmed(result.value.supportedAgents.joinToString("、") { it.label() })
                            }
                            is DataResult.Failed -> { noticeError = true; notice = result.message }
                        }
                    } finally { request.credential?.fill('\u0000'); busy = false }
                }
            } }
            inspected?.catalogError?.let { SettingsCaption(AppStrings.modelListUnavailableAddManually(it)) }
            val availableModels = (inspected?.models ?: saved?.models.orEmpty()).map { it.id to it.name } +
                manualModels.map { it to it } + listOfNotNull(model.takeIf { it.isNotBlank() }?.let { it to it })
            val modelOptions = availableModels.distinctBy { it.first }
            SettingsGroup { Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                modelAnchor = IntRect(origin.x.roundToInt(), origin.y.roundToInt(),
                    origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                SettingsAction(AppStrings.modelLabel(model.ifBlank { AppStrings.pleaseSelect }), enabled = !busy && !checking) { modelExpanded = true }
                FrostedMenu(modelExpanded, { modelExpanded = false; addModelDialog = false }, modelAnchor) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    if (addModelDialog) {
                        MenuSection(AppStrings.addModel) {
                            OutlinedTextField(newModelId, { newModelId = it; modelError = "" },
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                label = { Text(AppStrings.modelId) }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            if (modelError.isNotBlank()) MenuCaption(modelError)
                            MenuAction(AppStrings.add) {
                                val id = newModelId.trim()
                                if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() || it == ',' }) modelError = AppStrings.enterValidModelId
                                else {
                                    manualModels = manualModels + id
                                    selectedModels = selectedModels + id
                                    if (model.isBlank()) modelChanged(id)
                                    addModelDialog = false; modelExpanded = false
                                }
                            }
                            MenuAction(AppStrings.cancel) { addModelDialog = false; modelExpanded = false }
                        }
                    } else {
                    MenuSection(AppStrings.selectModel) {
                        OutlinedTextField(modelSearch, { modelSearch = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            label = { Text(AppStrings.searchModels) }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        MenuAction(AppStrings.addModel) {
                            newModelId = ""; modelError = ""; addModelDialog = true
                        }
                    }
                    val matches = modelOptions.filter { (id, name) ->
                        modelSearch.isBlank() || id.contains(modelSearch, true) || name.contains(modelSearch, true)
                    }
                    matches.take(30).forEach { (id, name) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable {
                                if (id != model) selectedModels = if (id in selectedModels) selectedModels - id else selectedModels + id
                            }.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(name, maxLines = 1, color = menuInk(), style = MaterialTheme.typography.bodyLarge)
                                if (name != id) Text(id, maxLines = 1, color = menuMuted(), style = MaterialTheme.typography.bodySmall)
                            }
                            if (id == model) Text(AppStrings.default, color = menuMuted(), style = MaterialTheme.typography.labelMedium)
                            else androidx.compose.material3.TextButton(onClick = { modelChanged(id); modelExpanded = false }) { Text(AppStrings.setAsDefault) }
                            if (id == model || id in selectedModels) AppIcon(AppIcons.Check, null, Modifier.size(20.dp), tint = menuTick())
                        }
                    }
                    if (matches.size > 30) MenuCaption(AppStrings.moreResultsSearch(matches.size - 30))
                    }
                    }
                }
            } }
            SettingsCaption(AppStrings.selectedDiscoveredModels((selectedModels + model).count { it.isNotBlank() }, inspected?.models?.size ?: 0))
            SettingsGroup { SettingsAction(AppStrings.saveGateway, enabled = !busy && !checking && inspected?.supportedAgents?.isNotEmpty() == true) {
                noticeError = false; busy = true; notice = AppStrings.recheckingAvailabilityBeforeSaving
                submit {
                    val request = edit()
                    var leave: String? = null
                    try {
                        when (val result = save(request)) {
                            is GatewaySaveResult.Saved -> {
                                key = ""; keyEdited = false; refresh()
                                leave = AppStrings.gatewaySavedModelsKept(result.agents.joinToString("、") { it.label() }, result.models.size)
                            }
                            is GatewaySaveResult.Failed -> { noticeError = true; notice = result.message }
                        }
                    } finally { request.credential?.fill('\u0000'); busy = false }
                    leave?.let { if (onSaved != null) onSaved(it) else notice = it }
                }
            } }
            if (!editingId.isNullOrBlank()) SettingsGroup {
                SettingsAction(AppStrings.deleteGateway, enabled = !busy && !checking) {
                    busy = true
                    submit { try { when (val result = delete(editingId)) {
                        OperationResult.Done -> { refresh(); onSaved?.invoke(AppStrings.gatewayDeleted) ?: back() }
                        is OperationResult.Failed -> { noticeError = true; notice = result.message }
                    } } finally { busy = false } }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = noticeError)
            if (saved != null) SettingsGroup {
                SettingsAction(AppStrings.testSavedConnection, enabled = !busy && !checking && savedMatches) {
                    connectionError = false; checking = true; connectionNotice = AppStrings.checkingSavedConfiguration
                    checkJob = checkScope.launch {
                        try { connectionNotice = when (val result = withTimeout(30_000) { check(saved) }) {
                            is DataResult.Loaded -> { connectionError = !result.value.passed; result.value.message }
                            is DataResult.Failed -> { connectionError = true; result.message }
                        } } catch (_: TimeoutCancellationException) { connectionError = true; connectionNotice = AppStrings.checkTimedOutSuccessNotConfirmed }
                        catch (e: CancellationException) { connectionError = true; connectionNotice = AppStrings.checkCancelledSuccessNotConfirmed; throw e }
                        catch (_: Exception) { connectionError = true; connectionNotice = AppStrings.connectionCheckIncompleteRetryLater }
                        finally { checking = false; checkJob = null }
                    }
                }
                if (checking) { GroupDivider(); SettingsAction(AppStrings.cancelCheck) { checkJob?.cancel() } }
            }
            if (connectionNotice.isNotBlank()) SettingsCaption(connectionNotice, error = connectionError)
        }
    }
}
