package com.github.ytlog.mobby.android.interaction.ui.gateway

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
        GatewayForm(profiles, vm::enqueue, vm.actions::saveGateway, vm::refresh, { editing = null }, vm.actions::checkGateway,
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
    save: suspend (GatewayEdit) -> GatewaySaveResult, refresh: suspend () -> Unit, back: () -> Unit,
    check: suspend (GatewayProfile) -> DataResult<GatewayCheckReport>, initial: AgentId = AgentId.CODEX,
    editingId: String? = profiles.singleOrNull()?.id, delete: suspend (String) -> OperationResult = { OperationResult.Failed("无法删除") },
    onSaved: ((String) -> Unit)? = null) {
    var agent by rememberSaveable { mutableStateOf(initial) }
    var selectedAgents by remember { mutableStateOf(setOf(initial)) }
    var providerId by remember { mutableStateOf(GatewayProviders.CUSTOM) }
    var endpoint by remember { mutableStateOf("") }
    var customEndpoints by remember { mutableStateOf<Map<AgentId, String>>(emptyMap()) }
    var model by remember { mutableStateOf("") }
    // Credentials deliberately excluded from SavedState/Room and never loaded back from the store.
    var key by remember { mutableStateOf("") }
    var keyEdited by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val checkScope = rememberCoroutineScope()
    val busy = saving || checking
    val group = profiles.filter { it.id == editingId }
    val profile = group.firstOrNull { it.agent == agent }
    var connectionNotice by remember(profile, endpoint, model, selectedAgents, keyEdited) { mutableStateOf("") }
    LaunchedEffect(agent) { notice = "" }
    LaunchedEffect(editingId, initial) {
        selectedAgents = if (group.isEmpty()) setOf(initial) else group.map { it.agent }.toSet()
        agent = group.firstOrNull()?.agent ?: initial
        val selectedProfile = group.firstOrNull()
        endpoint = selectedProfile?.endpoint.orEmpty()
        customEndpoints = group.associate { it.agent to it.endpoint }
        providerId = GatewayProviders.match(agent, endpoint)
        model = selectedProfile?.model.orEmpty()
        key = ""; keyEdited = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (!editingId.isNullOrBlank()) "编辑网关" else "添加网关", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption("选择服务后可勾选支持的 Agent。Codex / OpenCode 使用 Responses，Claude Code 使用 Messages。")
            val providers = GatewayProviders.all
            SettingsGroup("服务") {
                providers.forEachIndexed { index, provider ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(provider.label, providerId == provider.id, {
                        if (providerId != provider.id) {
                            providerId = provider.id
                            val next = selectedAgents.intersect(provider.agents).ifEmpty { setOf(provider.agents.first()) }
                            selectedAgents = next
                            agent = next.first()
                            endpoint = provider.endpoint(agent).orEmpty()
                            notice = ""
                        }
                    }, enabled = !busy)
                }
                GroupDivider()
                ChoiceRow("自定义", providerId == GatewayProviders.CUSTOM, {
                    if (providerId != GatewayProviders.CUSTOM) {
                        if (GatewayProviders.matches(agent, endpoint)) { endpoint = ""; customEndpoints = emptyMap() }
                        providerId = GatewayProviders.CUSTOM
                        notice = ""
                    }
                }, enabled = !busy)
            }
            val availableAgents = GatewayProviders.find(providerId)?.agents ?: AgentId.values().toSet()
            SettingsGroup("支持的 Agent") {
                AgentId.values().forEachIndexed { index, value ->
                    if (index > 0) GroupDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !busy && value in availableAgents) {
                        selectedAgents = if (value in selectedAgents) selectedAgents - value else selectedAgents + value
                        if (agent !in selectedAgents) agent = selectedAgents.firstOrNull() ?: value
                        endpoint = if (providerId != GatewayProviders.CUSTOM) GatewayProviders.find(providerId)?.endpoint(agent).orEmpty()
                            else customEndpoints[agent].orEmpty()
                        notice = ""
                    }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(value in selectedAgents, null, enabled = !busy && value in availableAgents)
                        Spacer(Modifier.width(8.dp))
                        Text(value.label(), color = if (value in availableAgents) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selectedAgents.size > 1 && providerId != GatewayProviders.CUSTOM) SettingsCaption(
                selectedAgents.joinToString(" · ") { "${it.label()}: ${GatewayProviders.find(providerId)?.endpoint(it)}" })
            SettingsCaption("切换服务只更新地址，请确认模型和密钥仍属于该服务。仅勾选服务提供原生接口的 Agent。")
            val stored = group.firstOrNull()?.hasCredential == true
            SettingsGroup {
                if (providerId == GatewayProviders.CUSTOM) selectedAgents.forEachIndexed { index, target ->
                    if (index > 0) GroupDivider()
                    SettingsField(customEndpoints[target].orEmpty(), {
                        customEndpoints = customEndpoints + (target to it)
                        if (target == agent) endpoint = it
                        notice = ""
                    }, if (selectedAgents.size == 1) "网关地址" else "${target.label()} 网关地址", enabled = !busy)
                } else SettingsField(endpoint, {}, "网关地址", enabled = false)
                GroupDivider()
                SettingsField(model, { model = it; notice = "" }, "模型名称", enabled = !busy)
                GroupDivider()
                SettingsField(key, { key = it; keyEdited = true; notice = "" }, if (stored && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）", enabled = !busy, visualTransformation = PasswordVisualTransformation())
                if (stored) {
                    GroupDivider()
                    SettingsAction("移除已保存密钥", enabled = !busy) { key = ""; keyEdited = true; notice = "" }
                }
            }
            SettingsCaption("凭据加密保存在设备。保存后会拉取模型列表，供会话里切换模型；保存成功不代表连通性验证通过。")
            if ((if (providerId == GatewayProviders.CUSTOM) customEndpoints.values else listOf(endpoint)).any { it.trim().startsWith("http://", ignoreCase = true) })
                SettingsCaption("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", error = true)
            SettingsGroup {
                SettingsAction("保存当前配置", enabled = !busy && selectedAgents.isNotEmpty()) {
                    saving = true; notice = ""
                    submit {
                        var leave: String? = null
                        try {
                            val endpoints = selectedAgents.associateWith { target ->
                                GatewayProviders.find(providerId)?.endpoint(target) ?: customEndpoints[target].orEmpty().trim()
                            }
                            val edit = GatewayEdit(editingId?.takeIf { it.isNotBlank() }, endpoints, model.trim(), if (keyEdited) key.toCharArray() else null)
                            when (val result = try { save(edit) } finally { edit.credential?.fill('\u0000') }) {
                                is GatewaySaveResult.Saved -> {
                                    key = ""; keyEdited = false; refresh()
                                    leave = when {
                                        result.catalogError != null -> "配置已保存，模型列表未能拉取。${result.catalogError}"
                                        result.models.isNotEmpty() -> "配置已保存，已拉取 ${result.models.size} 个模型，尚未测试连接"
                                        else -> "配置已保存，尚未测试连接"
                                    }
                                }
                                is GatewaySaveResult.Failed -> notice = result.message
                            }
                        } finally { saving = false }
                        leave?.let { message -> if (onSaved != null) onSaved(message) else notice = message }
                    }
                }
            }
            if (!editingId.isNullOrBlank()) SettingsGroup {
                SettingsAction("删除网关", enabled = !busy) {
                    saving = true
                    submit {
                        try { when (val result = delete(editingId)) {
                            OperationResult.Done -> { refresh(); onSaved?.invoke("网关已删除") ?: back() }
                            is OperationResult.Failed -> notice = result.message
                        } } finally { saving = false }
                    }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = notice.contains("失败") || notice.contains("未能") || notice.contains("不正确") || notice.contains("无效") || notice.contains("未确认"))
            SettingsCaption("测试连接会用已保存配置发送一个小型模型请求，可能产生少量费用；不验证 CLI、工具或会话恢复。")
            val matchesSaved = profile != null && profile.endpoint.isNotBlank() && profile.model.isNotBlank() && !keyEdited &&
                selectedAgents == group.map { it.agent }.toSet() && model.trim() == profile.model &&
                selectedAgents.all { target ->
                    val saved = group.firstOrNull { it.agent == target }?.endpoint
                    val entered = GatewayProviders.find(providerId)?.endpoint(target) ?: customEndpoints[target]?.trim()
                    saved == entered
                }
            SettingsGroup {
                SettingsAction("测试已保存连接", enabled = !busy && matchesSaved) {
                    val target = profile ?: return@SettingsAction
                    checking = true; cancelling = false; connectionNotice = "正在检查已保存配置…"
                    checkJob = checkScope.launch {
                        try {
                            connectionNotice = when (val result = withTimeout(30_000) { check(target) }) {
                                is DataResult.Loaded -> result.value.message
                                is DataResult.Failed -> result.message
                            }
                        } catch (_: TimeoutCancellationException) { connectionNotice = "检查超时，未判定成功" }
                        catch (e: CancellationException) { connectionNotice = "检查已取消，未判定成功"; throw e }
                        catch (_: Exception) { connectionNotice = "连接检查未完成，请稍后重试" }
                        finally { checking = false; cancelling = false; checkJob = null }
                    }
                }
                if (checking) {
                    GroupDivider()
                    SettingsAction("取消检查", enabled = !cancelling) { cancelling = true; connectionNotice = "正在取消检查…"; checkJob?.cancel() }
                }
            }
            if (!matchesSaved && !busy) SettingsCaption("请先保存当前修改，再测试连接。")
            if (connectionNotice.isNotBlank()) SettingsCaption(connectionNotice, error = connectionNotice.contains("失败") || connectionNotice.contains("超时") || connectionNotice.contains("取消") || connectionNotice.contains("未判定") || connectionNotice.contains("未完成"))
        }
    }
}
