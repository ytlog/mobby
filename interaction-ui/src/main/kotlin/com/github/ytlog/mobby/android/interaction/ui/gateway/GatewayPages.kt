package com.github.ytlog.mobby.android.interaction.ui.gateway

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

@Composable internal fun GatewayPage(vm: ConversationViewModel, back: () -> Unit) {
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val defaultGateway by vm.defaultGateway.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    if (editing == null) GatewayList(profiles, notice, defaultGateway, state.selected?.conversation?.config, agents, back,
        select = vm::chooseGateway, open = { editing = it ?: "new"; notice = "" })
    else key(editing) {
        GatewayForm(profiles, vm::enqueue, vm.actions::saveGateway, vm.actions::inspectGateway, vm::refresh, { editing = null }, vm.actions::checkGateway,
            editingId = editing.takeUnless { it == "new" } ?: "", delete = vm.actions::deleteGateway) { message ->
            notice = message
            editing = null
        }
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
        PageHeader("网关", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (configured.isEmpty()) EmptyPlaceholder("还没有配置网关", "添加后可以从列表中选择，并拉取模型用于切换")
            else SettingsGroup("已配置") {
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
                        }.semantics { contentDescription = "选择网关 ${representative.id}" }
                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                            Text(gatewaySummary(representative), style = MaterialTheme.typography.bodyLarge)
                            Text(entry.value.joinToString(" · ") { it.agent.label() }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val status = listOfNotNull(if (isCurrent) "当前会话" else null, if (defaultGateway?.id == representative.id) "新会话默认" else null).joinToString(" · ")
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (selected) AppIcon(AppIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        ActionIcon("编辑网关", { open(entry.key) }, AppIcons.Edit, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (selected && expandedGatewayId == representative.id && selectedProfile != null) {
                        val models = (selectedProfiles.flatMap { it.models } + GatewayModel(selectedProfile.model, selectedProfile.model))
                            .filter { it.id.isNotBlank() }.distinctBy { it.id }
                        GroupDivider()
                        Text("模型", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        models.forEach { item ->
                            ChoiceRow(if (item.name == item.id) item.id else "${item.name} · ${item.id}", selectedModel == item.id,
                                { select(selectedProfile, item.id, null) }, enabled = current != null)
                        }
                        val levels = agents.firstOrNull { it.agent == selectedProfile.agent }?.models?.get(selectedModel).orEmpty()
                        if (current != null && levels.isNotEmpty()) {
                            GroupDivider()
                            Text("思考程度", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ChoiceRow("默认", selectedReasoning == null, { select(selectedProfile, selectedModel, null) })
                            levels.forEach { level -> ChoiceRow(level, selectedReasoning == level, { select(selectedProfile, selectedModel, level) }) }
                        }
                    }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = notice.contains("未能"))
            SettingsGroup { SettingsAction("添加网关") { open(null) } }
            SettingsCaption("点按网关可展开模型与思考程度。选中的网关用于当前会话和新会话，模型与思考程度用于当前会话。")
        }
    }
}

internal fun gatewaySummary(profile: GatewayProfile): String {
    val providerId = GatewayProviders.match(profile.agent, profile.endpoint)
    val provider = GatewayProviders.find(providerId)?.label
        ?: runCatching { java.net.URI(profile.endpoint).host }.getOrNull()
        ?: "自定义"
    val catalog = when {
        profile.catalogError != null -> "模型列表未更新"
        profile.models.isNotEmpty() -> "${profile.models.size} 个模型"
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
    delete: suspend (String) -> OperationResult = { OperationResult.Failed("无法删除") },
    onSaved: ((String) -> Unit)? = null) {
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
    fun changed() { inspection = null; connectionNotice = ""; notice = "" }
    fun modelChanged(value: String) {
        model = value
        inspection = inspection?.copy(model = value, supportedAgents = emptySet())
        selectedModels = selectedModels + value
        connectionNotice = ""; notice = "请用当前模型重新探测，确认可用 Agent"
    }
    val inspected = inspection
    val savedMatches = saved != null && model.trim() == saved.model && !keyEdited &&
        group.all { candidates().forAgent(it.agent) == it.endpoint }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (!editingId.isNullOrBlank()) "编辑网关" else "添加网关", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption("填写 Base 地址和密钥后探测。应用会自动查找可用 Agent 和模型；小型探测请求可能产生少量费用。")
            SettingsGroup { Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                providerAnchor = IntRect(origin.x.roundToInt(), origin.y.roundToInt(),
                    origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                SettingsAction("服务：${GatewayProviders.find(providerId)?.label ?: "自定义"}", enabled = !busy && !checking) { providerExpanded = true }
                FrostedMenu(providerExpanded, { providerExpanded = false }, providerAnchor) {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                        MenuSection("选择服务") {
                        (GatewayProviders.all.map { it.id to it.label } + (GatewayProviders.CUSTOM to "自定义")).forEach { (id, label) ->
                            MenuOption(label, id == providerId) {
                                    providerId = id
                                    if (id != GatewayProviders.CUSTOM) baseEndpoint = gatewayBaseAddress(GatewayProviders.find(id)!!.candidates().responses)
                                    providerExpanded = false
                                    changed()
                                }
                        }
                        }
                    }
                }
            } }
            SettingsGroup {
                SettingsField(baseEndpoint, { baseEndpoint = it; changed() }, "Base 地址", enabled = !busy && !checking && providerId == GatewayProviders.CUSTOM)
                GroupDivider()
                SettingsField(key, { key = it; keyEdited = true; changed() },
                    if (saved?.hasCredential == true && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）",
                    enabled = !busy && !checking, visualTransformation = PasswordVisualTransformation())
                if (saved?.hasCredential == true) {
                    GroupDivider()
                    SettingsAction("移除已保存密钥", enabled = !busy && !checking) { key = ""; keyEdited = true; changed() }
                }
            }
            val supported = inspected?.supportedAgents ?: group.map { it.agent }.toSet()
            if (supported.isNotEmpty()) SettingsCaption("${if (inspected != null) "已确认" else "已保存"}的 Agent：${supported.joinToString("、") { it.label() }}")
            if (listOf(candidates().responses, candidates().messages).any { it.startsWith("http://", ignoreCase = true) })
                SettingsCaption("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", error = true)
            SettingsGroup { SettingsAction("探测支持的 Agent 和模型", enabled = !busy && !checking && candidates().responses.isNotBlank()) {
                if (providerId == GatewayProviders.CUSTOM) baseEndpoint = gatewayBaseAddress(baseEndpoint)
                busy = true; notice = "正在探测可用 Agent 和模型…"
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
                                notice = if (result.value.supportedAgents.isEmpty()) "未确认任何可用 Agent；请检查地址、模型、密钥和额度"
                                    else "已确认：${result.value.supportedAgents.joinToString("、") { it.label() }}"
                            }
                            is DataResult.Failed -> notice = result.message
                        }
                    } finally { request.credential?.fill('\u0000'); busy = false }
                }
            } }
            inspected?.catalogError?.let { SettingsCaption("模型列表不可用：$it。可从模型菜单手动添加。") }
            val availableModels = (inspected?.models ?: saved?.models.orEmpty()).map { it.id to it.name } +
                manualModels.map { it to it } + listOfNotNull(model.takeIf { it.isNotBlank() }?.let { it to it })
            val modelOptions = availableModels.distinctBy { it.first }
            SettingsGroup { Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                modelAnchor = IntRect(origin.x.roundToInt(), origin.y.roundToInt(),
                    origin.x.roundToInt() + coordinates.size.width, origin.y.roundToInt() + coordinates.size.height)
            }) {
                SettingsAction("模型：${model.ifBlank { "请选择" }}", enabled = !busy && !checking) { modelExpanded = true }
                FrostedMenu(modelExpanded, { modelExpanded = false; addModelDialog = false }, modelAnchor) {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    if (addModelDialog) {
                        MenuSection("添加模型") {
                            OutlinedTextField(newModelId, { newModelId = it; modelError = "" },
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                label = { Text("模型 ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                            if (modelError.isNotBlank()) MenuCaption(modelError)
                            MenuAction("添加") {
                                val id = newModelId.trim()
                                if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() || it == ',' }) modelError = "请输入一个有效的模型 ID"
                                else {
                                    manualModels = manualModels + id
                                    selectedModels = selectedModels + id
                                    if (model.isBlank()) modelChanged(id)
                                    addModelDialog = false; modelExpanded = false
                                }
                            }
                            MenuAction("取消") { addModelDialog = false; modelExpanded = false }
                        }
                    } else {
                    MenuSection("选择模型") {
                        OutlinedTextField(modelSearch, { modelSearch = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            label = { Text("搜索模型") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                        MenuAction("添加模型") {
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
                            if (id == model) Text("默认", color = menuMuted(), style = MaterialTheme.typography.labelMedium)
                            else androidx.compose.material3.TextButton(onClick = { modelChanged(id); modelExpanded = false }) { Text("设为默认") }
                            if (id == model || id in selectedModels) AppIcon(AppIcons.Check, null, Modifier.size(20.dp), tint = menuTick())
                        }
                    }
                    if (matches.size > 30) MenuCaption("还有 ${matches.size - 30} 个结果，请搜索")
                    }
                    }
                }
            } }
            SettingsCaption("已选 ${(selectedModels + model).count { it.isNotBlank() }} 个模型；已发现 ${inspected?.models?.size ?: 0} 个。默认模型变更后需重新探测。")
            SettingsGroup { SettingsAction("保存网关", enabled = !busy && !checking && inspected?.supportedAgents?.isNotEmpty() == true) {
                busy = true; notice = "保存前正在重新确认可用性…"
                submit {
                    val request = edit()
                    var leave: String? = null
                    try {
                        when (val result = save(request)) {
                            is GatewaySaveResult.Saved -> {
                                key = ""; keyEdited = false; refresh()
                                leave = "网关已保存：${result.agents.joinToString("、") { it.label() }}；保留 ${result.models.size} 个模型"
                            }
                            is GatewaySaveResult.Failed -> notice = result.message
                        }
                    } finally { request.credential?.fill('\u0000'); busy = false }
                    leave?.let { if (onSaved != null) onSaved(it) else notice = it }
                }
            } }
            if (!editingId.isNullOrBlank()) SettingsGroup {
                SettingsAction("删除网关", enabled = !busy && !checking) {
                    busy = true
                    submit { try { when (val result = delete(editingId)) {
                        OperationResult.Done -> { refresh(); onSaved?.invoke("网关已删除") ?: back() }
                        is OperationResult.Failed -> notice = result.message
                    } } finally { busy = false } }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = notice.contains("失败") || notice.contains("未确认") || notice.contains("不可用"))
            if (saved != null) SettingsGroup {
                SettingsAction("测试已保存连接", enabled = !busy && !checking && savedMatches) {
                    checking = true; connectionNotice = "正在检查已保存配置…"
                    checkJob = checkScope.launch {
                        try { connectionNotice = when (val result = withTimeout(30_000) { check(saved) }) {
                            is DataResult.Loaded -> result.value.message
                            is DataResult.Failed -> result.message
                        } } catch (_: TimeoutCancellationException) { connectionNotice = "检查超时，未判定成功" }
                        catch (e: CancellationException) { connectionNotice = "检查已取消，未判定成功"; throw e }
                        catch (_: Exception) { connectionNotice = "连接检查未完成，请稍后重试" }
                        finally { checking = false; checkJob = null }
                    }
                }
                if (checking) { GroupDivider(); SettingsAction("取消检查") { checkJob?.cancel() } }
            }
            if (connectionNotice.isNotBlank()) SettingsCaption(connectionNotice, error = connectionNotice.contains("失败") || connectionNotice.contains("未判定"))
        }
    }
}
