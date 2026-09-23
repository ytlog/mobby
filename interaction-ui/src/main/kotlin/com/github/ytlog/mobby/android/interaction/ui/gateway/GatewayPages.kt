package com.github.ytlog.mobby.android.interaction.ui.gateway

import com.github.ytlog.mobby.android.interaction.domain.gateway.*

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.ui.*
import kotlinx.coroutines.*

@Composable internal fun GatewayPage(vm: ConversationViewModel, back: () -> Unit) {
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val defaultGateway by vm.defaultGateway.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    if (editing == null) GatewayList(profiles, notice, defaultGateway, state.selected?.conversation?.config, back,
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
    back: () -> Unit, select: (GatewayProfile) -> Unit, open: (String?) -> Unit) {
    val configured = profiles.filter { it.endpoint.isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        PageHeader("网关", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (configured.isEmpty()) EmptyPlaceholder("还没有配置网关", "添加后可以从列表中选择，并拉取模型用于切换")
            else SettingsGroup("已配置") {
                configured.groupBy { it.id }.entries.forEachIndexed { index, entry ->
                    if (index > 0) GroupDivider()
                    val representative = entry.value.first()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 8.dp)) {
                            Text(gatewaySummary(representative), style = MaterialTheme.typography.bodyLarge)
                            Text(entry.value.joinToString(" · ") { it.agent.label() }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ActionIcon("编辑网关", { open(entry.key) }, AppIcons.Edit, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                    }
                    entry.value.forEach { profile ->
                        val selected = defaultGateway?.id == profile.id && defaultGateway.agent == profile.agent
                        val isCurrent = current?.gatewayProfile == profile.id && current.agent == profile.agent
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton) { select(profile) }
                            .padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(profile.agent.glyph(), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(12.dp))
                            Text(profile.agent.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            val status = listOfNotNull(if (isCurrent) "当前会话" else null, if (selected) "新会话默认" else null).joinToString(" · ")
                            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (selected) AppIcon(AppIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = notice.contains("未能"))
            SettingsGroup { SettingsAction("添加网关") { open(null) } }
            SettingsCaption("每个网关可支持一个或多个 Agent。点按 Agent 可用于当前会话，并设为新会话默认。")
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
    var responsesEndpoint by remember { mutableStateOf("") }
    var messagesEndpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var keyEdited by remember { mutableStateOf(false) }
    var inspection by remember { mutableStateOf<GatewayInspectionResult?>(null) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelSearch by remember { mutableStateOf("") }
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
        responsesEndpoint = group.firstOrNull { it.agent != AgentId.CLAUDE_CODE }?.endpoint
            ?: first?.endpoint.orEmpty()
        messagesEndpoint = group.firstOrNull { it.agent == AgentId.CLAUDE_CODE }?.endpoint
            ?.takeIf { it != responsesEndpoint }.orEmpty()
        model = first?.model.orEmpty()
        selectedModels = first?.models?.map { it.id }?.toSet().orEmpty()
        key = ""; keyEdited = false; inspection = null
    }
    fun candidates(): GatewayAddresses = GatewayProviders.find(providerId)?.candidates()
        ?: GatewayAddresses(responsesEndpoint.trim(), messagesEndpoint.trim())
    fun edit() = GatewayEdit(editingId?.takeIf { it.isNotBlank() }, candidates(), model.trim(),
        if (keyEdited) key.toCharArray() else null, selectedModels)
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
            SettingsCaption("填写地址和密钥后探测。应用会用所选模型分别请求 Responses 与 Messages，并自动生成通过验证的 Agent。小型请求可能产生少量费用。")
            SettingsGroup("服务") {
                GatewayProviders.all.forEachIndexed { index, provider ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(provider.label, providerId == provider.id, {
                        providerId = provider.id
                        val endpoints = provider.candidates()
                        responsesEndpoint = endpoints.responses
                        messagesEndpoint = endpoints.messages
                        changed()
                    }, enabled = !busy && !checking)
                }
                GroupDivider()
                ChoiceRow("自定义", providerId == GatewayProviders.CUSTOM, {
                    providerId = GatewayProviders.CUSTOM; changed()
                }, enabled = !busy && !checking)
            }
            SettingsGroup {
                SettingsField(responsesEndpoint, { responsesEndpoint = it; changed() }, "网关地址", enabled = !busy && !checking && providerId == GatewayProviders.CUSTOM)
                if (providerId == GatewayProviders.CUSTOM) {
                    GroupDivider()
                    SettingsField(messagesEndpoint, { messagesEndpoint = it; changed() }, "Messages 地址（留空则使用上方地址）", enabled = !busy && !checking)
                }
                GroupDivider()
                SettingsField(model, ::modelChanged, "探测模型（可先留空）", enabled = !busy && !checking)
                GroupDivider()
                SettingsField(key, { key = it; keyEdited = true; changed() },
                    if (saved?.hasCredential == true && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）",
                    enabled = !busy && !checking, visualTransformation = PasswordVisualTransformation())
                if (saved?.hasCredential == true) {
                    GroupDivider()
                    SettingsAction("移除已保存密钥", enabled = !busy && !checking) { key = ""; keyEdited = true; changed() }
                }
            }
            if (listOf(candidates().responses, candidates().messages).any { it.startsWith("http://", ignoreCase = true) })
                SettingsCaption("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", error = true)
            SettingsGroup { SettingsAction("探测支持的 Agent 和模型", enabled = !busy && !checking && candidates().responses.isNotBlank()) {
                busy = true; notice = "正在探测模型和原生协议…"
                submit {
                    val request = edit()
                    try {
                        when (val result = inspect(request)) {
                            is DataResult.Loaded -> {
                                inspection = result.value
                                model = result.value.model
                                val available = result.value.models.map { it.id }.toSet()
                                selectedModels = selectedModels.intersect(available) + setOf(model).intersect(available)
                                notice = if (result.value.supportedAgents.isEmpty()) "未确认任何可用 Agent；请检查地址、模型、密钥和额度"
                                    else "已确认：${result.value.supportedAgents.joinToString("、") { it.label() }}"
                            }
                            is DataResult.Failed -> notice = result.message
                        }
                    } finally { request.credential?.fill('\u0000'); busy = false }
                }
            } }
            inspected?.let { result ->
                result.catalogError?.let { SettingsCaption("模型列表不可用：$it。可手动填写模型并再次探测。") }
                if (result.models.isNotEmpty()) {
                    SettingsCaption("已选 ${selectedModels.size} 个模型；默认模型始终保留。输入关键词可筛选列表。")
                    SettingsGroup { SettingsField(modelSearch, { modelSearch = it }, "筛选模型", enabled = !busy && !checking) }
                    val matches = result.models.filter { modelSearch.isBlank() || it.id.contains(modelSearch, true) || it.name.contains(modelSearch, true) }
                    SettingsGroup("可用模型") {
                        matches.take(30).forEachIndexed { index, item ->
                            if (index > 0) GroupDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy && !checking && item.id != model) {
                                selectedModels = if (item.id in selectedModels) selectedModels - item.id else selectedModels + item.id
                            }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(item.id in selectedModels || item.id == model, null, enabled = !busy && !checking && item.id != model)
                                Spacer(Modifier.width(8.dp))
                                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (item.id != model) androidx.compose.material3.TextButton(onClick = { modelChanged(item.id) }, enabled = !busy && !checking) { Text("设为默认") }
                            }
                        }
                    }
                    if (matches.size > 30) SettingsCaption("还匹配 ${matches.size - 30} 个模型，请继续缩小关键词。")
                }
            }
            SettingsGroup { SettingsAction("保存网关", enabled = !busy && !checking && inspected?.supportedAgents?.isNotEmpty() == true) {
                busy = true; notice = "保存前正在重新确认协议…"
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
