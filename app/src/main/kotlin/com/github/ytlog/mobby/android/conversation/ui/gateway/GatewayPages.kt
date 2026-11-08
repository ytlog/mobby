package com.github.ytlog.mobby.android.conversation.ui.gateway

import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.ui.*
import kotlinx.coroutines.*

internal fun gatewayBaseAddress(value: String): String = value.trim().trimEnd('/')
    .replace(Regex("(?i)/(?:responses|messages|chat/completions)$"), "")

@Composable internal fun GatewayPage(vm: ConversationViewModel, startAdding: Boolean = false,
                                     signIn: suspend () -> GatewaySignInResult = { GatewaySignInResult.Failed(AppStrings.gatewaySignInUnavailable) },
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
        val id = editing.takeUnless { it == "new" } ?: ""
        val draft = remember(vm, id) { vm.gatewayDraft(id) }
        fun closeEditor() {
            vm.clearGatewayDraft()
            editing = null
        }
        LaunchedEffect(draft.savedMessage) {
            draft.savedMessage?.let { message -> notice = message; closeEditor() }
        }
        GatewayForm(visibleProfiles, vm::enqueue, vm.actions::saveGateway, vm.actions::fetchGatewayModels, vm::refresh, ::closeEditor, vm.actions::checkGateway,
            editingId = id, delete = vm.actions::deleteGateway, signIn = signIn,
            onSaved = { draft.savedMessage = it }, retainedDraft = draft)
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
                        var search by rememberSaveable(representative.id) { mutableStateOf("") }
                        // Services may list hundreds of models; past a handful, search and keep the selected one in view.
                        val shown = if (models.size <= 8) models else {
                            val matches = models.filter { search.isBlank() || it.id.contains(search, true) || it.name.contains(search, true) }.take(30)
                            models.filter { it.id == selectedModel && it !in matches } + matches
                        }
                        if (models.size > 8) Box(Modifier.padding(horizontal = 16.dp)) {
                            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text(AppStrings.searchModels) },
                                singleLine = true, shape = RoundedCornerShape(12.dp))
                        }
                        shown.forEach { item ->
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
    val catalog = when {
        profile.catalogError != null -> AppStrings.modelListNotUpdated
        profile.models.isNotEmpty() -> AppStrings.models(profile.models.size)
        else -> null
    }
    return listOfNotNull(gatewayLabel(profile), profile.model.takeIf { it.isNotBlank() }, catalog).joinToString(" · ")
}

internal fun gatewayLabel(profile: GatewayProfile): String {
    val providerId = GatewayProviders.match(profile.agent, profile.endpoint)
    return (if (profile.temporary) AppStrings.localModelService else null)
        ?: GatewayProviders.matchGroup(listOf(profile))?.label
        ?: GatewayProviders.find(providerId)?.label
        ?: runCatching { java.net.URI(profile.endpoint).host }.getOrNull()
        ?: AppStrings.custom
}


@Composable private fun GatewayFormAction(label: String, busy: Boolean, enabled: Boolean, loadingTag: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = onButtonColor(),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp).testTag(loadingTag), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Services as cards; the last one takes any address. Nothing is preselected for a new setup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ServiceChoices(selectedId: String?, selectedSite: GatewaySite, enabled: Boolean, choose: (String, GatewaySite) -> Unit) {
    val options = GatewayProviders.ordered(LanguagePreferences.current).map { Triple(it.id, it.site, it.label) } +
        Triple(GatewayProviders.CUSTOM, GatewaySite.SHARED, AppStrings.otherGatewayService)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, site, label) ->
            val selected = id == selectedId
            Surface(shape = RoundedCornerShape(12.dp), color = cardColor(),
                border = BorderStroke(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { choose(id, if (selected) selectedSite else site) }) {
                Text(label, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
            }
        }
    }
}

@Composable private fun ModelPicker(models: List<GatewayModel>, selected: Set<String>, default: String, enabled: Boolean,
    toggle: (String) -> Unit, makeDefault: (String) -> Unit, clear: () -> Unit, other: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val matches = models.filter { search.isBlank() || it.id.contains(search, true) || it.name.contains(search, true) }
    SettingsGroup {
        SettingsField(search, { search = it }, AppStrings.searchModels, enabled = enabled)
        matches.take(30).forEach { model ->
            GroupDivider()
            val checked = model.id in selected
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle(model.id) })
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(8.dp))
                    Text(if (model.name == model.id) model.id else "${model.name} · ${model.id}")
                }
                if (model.id == default) Text(AppStrings.default, Modifier.padding(horizontal = 12.dp))
                else androidx.compose.material3.TextButton(onClick = { makeDefault(model.id) }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = AppStrings.setGatewayModelDefault(model.id) }) {
                    Text(AppStrings.setGatewayDefault)
                }
            }
        }
        if (matches.size > 30) { GroupDivider(); SettingsCaption(AppStrings.moreResultsSearch(matches.size - 30)) }
        GroupDivider()
        if (selected.isNotEmpty()) {
            SettingsAction(AppStrings.clearGatewayModelSelection, enabled = enabled, onClick = clear)
            GroupDivider()
        }
        SettingsAction(AppStrings.useAnotherModelId, enabled = enabled, onClick = other)
    }
}

internal class GatewayFormDraft {
    var initialized = false
    var savedMessage by mutableStateOf<String?>(null)
    var providerId by mutableStateOf<String?>(null)
    var providerSite by mutableStateOf(GatewaySite.SHARED)
    var baseEndpoint by mutableStateOf("")
    var model by mutableStateOf("")
    var key by mutableStateOf("")
    var keyEdited by mutableStateOf(false)
    var catalog by mutableStateOf<List<GatewayModel>?>(null)
    var catalogError by mutableStateOf<String?>(null)
    var manualId by mutableStateOf("")
    var manualOpen by mutableStateOf(false)
    var selectingModels by mutableStateOf(false)
    var selectedModels by mutableStateOf<Set<String>?>(null)
    var fetchingModels by mutableStateOf(false)
    var advanced by mutableStateOf(false)
    var notice by mutableStateOf("")
    var noticeError by mutableStateOf(false)
    var connectionError by mutableStateOf(false)
    var connectionNotice by mutableStateOf("")
    var busy by mutableStateOf(false)
    var connecting by mutableStateOf(false)
}

@Composable internal fun GatewayForm(profiles: List<GatewayProfile>, submit: (suspend () -> Unit) -> Unit,
    save: suspend (GatewayEdit) -> GatewaySaveResult,
    fetchModels: suspend (GatewayEdit) -> DataResult<GatewayCatalogResult>,
    refresh: suspend () -> Unit, back: () -> Unit,
    check: suspend (GatewayProfile) -> DataResult<GatewayCheckReport>,
    editingId: String? = profiles.singleOrNull()?.id,
    delete: suspend (String) -> OperationResult = { OperationResult.Failed(AppStrings.cannotDelete) },
    onSaved: ((String) -> Unit)? = null,
    signIn: suspend () -> GatewaySignInResult = { GatewaySignInResult.Failed(AppStrings.gatewaySignInUnavailable) },
    openLink: ((String) -> Unit)? = null,
    retainedDraft: GatewayFormDraft? = null,
) {
    val group = profiles.filter { it.id == editingId }
    val saved = group.firstOrNull()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val draft = retainedDraft ?: remember(editingId) { GatewayFormDraft() }
    var providerId by draft::providerId
    var providerSite by draft::providerSite
    var baseEndpoint by draft::baseEndpoint
    var model by draft::model
    var key by draft::key
    var keyEdited by draft::keyEdited
    var catalog by draft::catalog
    var catalogError by draft::catalogError
    var manualId by draft::manualId
    var manualOpen by draft::manualOpen
    var selectingModels by draft::selectingModels
    var selectedModels by draft::selectedModels
    var fetchingModels by draft::fetchingModels
    var advanced by draft::advanced
    var notice by draft::notice
    var noticeError by draft::noticeError
    var connectionError by draft::connectionError
    var connectionNotice by draft::connectionNotice
    var busy by draft::busy
    var connecting by draft::connecting
    var checking by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var signInJob by remember { mutableStateOf<Job?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val checkScope = rememberCoroutineScope()
    LaunchedEffect(draft) {
        if (draft.initialized) return@LaunchedEffect
        draft.initialized = true
        val first = group.firstOrNull()
        val provider = GatewayProviders.matchGroup(group)
        providerId = if (first == null) null else provider?.id ?: GatewayProviders.CUSTOM
        providerSite = provider?.site ?: GatewaySite.SHARED
        baseEndpoint = gatewayBaseAddress(provider?.candidates()?.responses ?: first?.endpoint.orEmpty())
        model = first?.model.orEmpty()
        catalog = first?.models?.takeIf { it.isNotEmpty() }
        selectedModels = first?.let { catalog.orEmpty().map { entry -> entry.id }.toSet() + it.model }
        key = ""; keyEdited = false
    }
    val idle = !busy && !checking && !signingIn
    fun candidates(): GatewayAddresses = providerId?.let { GatewayProviders.find(it, providerSite)?.candidates() }
        ?: gatewayBaseAddress(baseEndpoint).let { GatewayAddresses(it, it) }
    fun edit(defaultModel: String, models: Set<String> = emptySet()) = GatewayEdit(editingId?.takeIf { it.isNotBlank() }, candidates(), defaultModel,
        if (keyEdited || saved == null) key.toCharArray() else null, models)
    fun changed() {
        noticeError = false; notice = ""; connectionNotice = ""
        catalog = null; catalogError = null; selectingModels = false
    }
    fun sourceChanged() {
        key = ""; keyEdited = true
        model = saved?.model.orEmpty(); manualId = ""; manualOpen = false; selectedModels = null
        changed()
    }
    val savedMatches = saved != null && model.trim() == saved.model && !keyEdited &&
        group.all { candidates().forAgent(it.agent) == it.endpoint }
    fun chooseProvider(id: String, site: GatewaySite) {
        if (providerId == id && providerSite == site) return
        providerId = id; providerSite = site
        baseEndpoint = GatewayProviders.find(id, site)?.let { gatewayBaseAddress(it.candidates().responses) }.orEmpty()
        sourceChanged()
    }
    fun openOfficial(url: String) {
        try { if (openLink != null) openLink(url) else uriHandler.openUri(url) }
        catch (_: Exception) { noticeError = true; notice = AppStrings.cannotOpenThisLink }
    }
    suspend fun loadCatalog(): Boolean {
        val request = edit(model.trim())
        return when (val result = try { fetchModels(request) } finally { request.credential?.fill('\u0000') }) {
            is DataResult.Failed -> {
                noticeError = true; notice = result.message
                false
            }
            is DataResult.Loaded -> {
                catalog = result.value.models; catalogError = result.value.catalogError
                true
            }
        }
    }
    fun suggestedDefault(listed: List<GatewayModel>): String? =
        model.takeIf { id -> listed.any { it.id == id } }
            ?: providerId?.let { GatewayRecommendations.pick(it, listed) } ?: listed.singleOrNull()?.id

    fun showModelSelection(listed: List<GatewayModel>) {
        if (selectedModels == null) {
            model = suggestedDefault(listed).orEmpty()
            selectedModels = setOfNotNull(model.takeIf { it.isNotBlank() })
        }
        selectingModels = true
    }

    fun configureModels() {
        busy = true; fetchingModels = true; notice = ""; noticeError = false
        submit {
            try { if (loadCatalog()) showModelSelection(catalog.orEmpty()) }
            finally { fetchingModels = false; busy = false }
        }
    }

    fun connect() {
        if (providerId == GatewayProviders.CUSTOM) baseEndpoint = gatewayBaseAddress(baseEndpoint)
        noticeError = false; notice = ""; connectionNotice = ""; busy = true; connecting = true
        submit {
            var leave: String? = null
            try {
                if (!selectingModels && !loadCatalog()) return@submit
                val listed = catalog.orEmpty()
                val typed = manualId.trim().takeIf { manualOpen || listed.isEmpty() }.orEmpty()
                if (typed.isNotEmpty() && (typed.length > 200 || typed.any { it.isISOControl() || it == ',' })) {
                    noticeError = true; notice = AppStrings.enterValidModelId; return@submit
                }
                val target = typed.ifEmpty { null } ?: selectedModels.let { selected ->
                    if (selected == null) suggestedDefault(listed) else model.takeIf { it in selected }
                }
                if (target == null) {
                    if (listed.isEmpty()) manualOpen = true else showModelSelection(listed)
                    return@submit
                }
                val models = (selectedModels ?: listed.map { it.id }.toSet()) + target
                if (models.size > 2_000) {
                    noticeError = true; notice = AppStrings.invalidModelList; return@submit
                }
                val request = edit(target, models)
                val result = try { save(request) } finally { request.credential?.fill('\u0000') }
                when (result) {
                    is GatewaySaveResult.Saved -> {
                        model = target; selectedModels = models
                        key = ""; keyEdited = false; refresh()
                        leave = AppStrings.gatewayConnected(result.agents.sortedBy { it.ordinal }.joinToString("、") { it.label() })
                    }
                    is GatewaySaveResult.Failed -> { noticeError = true; notice = result.message }
                }
            } finally { connecting = false; busy = false }
            leave?.let { if (onSaved != null) onSaved(it) else notice = it }
        }
    }
    val guide = providerId?.let { GatewayProviderGuides.find(it, providerSite) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (!editingId.isNullOrBlank()) AppStrings.editGateway else AppStrings.addGateway, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption(AppStrings.gatewaySetupHelp)
            ServiceChoices(providerId, providerSite, idle) { id, site -> chooseProvider(id, site) }
            val chosenId = providerId ?: return@Column
            if (chosenId == "openrouter") SettingsGroup {
                GatewayFormAction(if (signingIn) AppStrings.signingInGateway else AppStrings.signInOpenRouter, signingIn,
                    enabled = idle, loadingTag = "gateway-sign-in-loading") {
                    signingIn = true; notice = ""; noticeError = false
                    signInJob = checkScope.launch {
                        try {
                            when (val result = signIn()) {
                                is GatewaySignInResult.Credential -> try {
                                    ensureActive()
                                    key = result.value.concatToString(); keyEdited = true; changed()
                                    notice = AppStrings.gatewayKeyReceived
                                } finally { result.value.fill('\u0000') }
                                is GatewaySignInResult.Failed -> { noticeError = true; notice = result.message }
                            }
                        } catch (e: CancellationException) { notice = AppStrings.gatewaySignInCancelled; throw e }
                        catch (_: Exception) { noticeError = true; notice = AppStrings.gatewaySignInFailed }
                        finally { signingIn = false; signInJob = null }
                    }
                }
                if (signingIn) { GroupDivider(); SettingsAction(AppStrings.cancelGatewaySignIn) { signInJob?.cancel() } }
            }
            SettingsGroup {
                if (chosenId == GatewayProviders.CUSTOM) {
                    SettingsField(baseEndpoint, { baseEndpoint = it; sourceChanged() }, AppStrings.baseAddress, enabled = idle)
                    GroupDivider()
                }
                SettingsField(key, { key = it; keyEdited = true; changed() },
                    when {
                        saved?.hasCredential == true && !keyEdited -> AppStrings.keySavedEnterAReplacement
                        chosenId == GatewayProviders.CUSTOM -> AppStrings.apiKeyOptionalWithoutAuthentication
                        else -> AppStrings.apiKey
                    },
                    enabled = idle, visualTransformation = PasswordVisualTransformation(),
                    trailing = {
                        androidx.compose.material3.TextButton(onClick = {
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            if (pasted.isEmpty()) { noticeError = true; notice = AppStrings.clipboardHasNoText }
                            else { key = pasted; keyEdited = true; changed() }
                        }, enabled = idle) { Text(AppStrings.pasteKey) }
                    })
                guide?.keyUrl?.let { url ->
                    GroupDivider()
                    SettingsAction(AppStrings.gatewayProviderKeyLink, enabled = idle) { openOfficial(url) }
                }
                if (saved?.hasCredential == true) {
                    GroupDivider()
                    SettingsAction(AppStrings.removeSavedKey, enabled = idle) { key = ""; keyEdited = true; changed() }
                }
            }
            guide?.let { SettingsCaption(it.summary) }
            val supported = group.map { it.agent }.toSet()
            if (supported.isNotEmpty()) SettingsCaption(AppStrings.agents(AppStrings.savedLabel, supported.joinToString("、") { it.label() }))
            if (listOf(candidates().responses, candidates().messages).any { it.startsWith("http://", ignoreCase = true) })
                SettingsCaption(AppStrings.httpSendsKeysAndContentInPlainTextUse, error = true)
            val listed = catalog.orEmpty()
            val showManual = manualOpen || (catalog != null && listed.isEmpty())
            if (selectingModels) {
                val selected = selectedModels.orEmpty()
                val available = (listed + selected.map { GatewayModel(it, it) }).distinctBy { it.id }
                if (available.isNotEmpty()) {
                    SettingsCaption(AppStrings.gatewayModelSelectionHelp)
                    ModelPicker(available, selected, model, idle, toggle = { id ->
                        selectedModels = if (id in selected) selected - id else selected + id
                        if (id == model) model = ""
                    }, makeDefault = { id ->
                        selectedModels = selected + id; model = id; manualOpen = false; manualId = ""
                    }, clear = { selectedModels = emptySet(); model = "" }, other = { manualOpen = true })
                }
            } else SettingsGroup {
                GatewayFormAction(if (fetchingModels) AppStrings.loadingGatewayModels else AppStrings.configureGatewayModels,
                    fetchingModels, idle && candidates().responses.isNotBlank(), "gateway-models-loading", ::configureModels)
                if (model.isNotBlank()) SettingsCaption(AppStrings.modelLabel(model))
            }
            if (showManual) {
                catalogError?.takeIf { listed.isEmpty() }?.let { SettingsCaption(AppStrings.noModelListEnterModelId(it)) }
                SettingsGroup { SettingsField(manualId, { manualId = it; notice = "" }, AppStrings.modelId, enabled = idle) }
            }
            SettingsGroup {
                GatewayFormAction(if (connecting) AppStrings.connectingGateway else AppStrings.connectGateway, connecting,
                    enabled = idle && candidates().responses.isNotBlank() &&
                        (!selectingModels || model in selectedModels.orEmpty() || showManual && manualId.isNotBlank()),
                    loadingTag = "gateway-connect-loading") { connect() }
            }
            SettingsCaption(AppStrings.connectUsesASmallRequest)
            if (notice.isNotBlank()) SettingsCaption(notice, error = noticeError)
            val sites = GatewayProviders.sites(chosenId)
            val hasAdvanced = sites.size > 1 || guide?.helpUrl != null || saved != null
            if (hasAdvanced) {
                SettingsGroup { SettingsAction(AppStrings.advancedSettings, enabled = !busy) { advanced = !advanced } }
                if (advanced) {
                    if (sites.size > 1) {
                        SettingsCaption(AppStrings.gatewayProviderSiteHelp)
                        SettingsGroup {
                            sites.sortedBy { it.site != (if (LanguagePreferences.current == com.github.ytlog.mobby.android.localization.AppLanguage.CHINESE) GatewaySite.CHINA else GatewaySite.INTERNATIONAL) }
                                .forEachIndexed { index, option ->
                                    if (index > 0) GroupDivider()
                                    ChoiceRow(if (option.site == GatewaySite.CHINA) AppStrings.gatewayChinaSite else AppStrings.gatewayInternationalSite,
                                        option.site == providerSite, { chooseProvider(option.id, option.site) }, enabled = idle)
                                }
                        }
                    }
                    guide?.helpUrl?.let { url -> SettingsGroup { SettingsAction(AppStrings.gatewayProviderHelpLink, enabled = idle) { openOfficial(url) } } }
                    if (saved != null) SettingsGroup {
                        SettingsAction(AppStrings.testSavedConnection, enabled = idle && savedMatches) {
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
                        GroupDivider()
                        SettingsAction(AppStrings.deleteGateway, enabled = idle) {
                            val id = editingId ?: return@SettingsAction
                            busy = true
                            submit { try { when (val result = delete(id)) {
                                OperationResult.Done -> { refresh(); onSaved?.invoke(AppStrings.gatewayDeleted) ?: back() }
                                is OperationResult.Failed -> { noticeError = true; notice = result.message }
                            } } finally { busy = false } }
                        }
                    }
                    if (connectionNotice.isNotBlank()) SettingsCaption(connectionNotice, error = connectionError)
                }
            }
        }
    }
}
