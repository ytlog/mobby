package com.github.ytlog.mobby.android.interaction.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.github.ytlog.mobby.android.device.DeviceStorage
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*

@Composable internal fun TextEditDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value) }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable internal fun WorkspacePicker(vm: ConversationViewModel, selected: String, owner: String, enabled: Boolean = true, select: (String) -> Unit) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val error by vm.workspaceError.collectAsStateWithLifecycle()
    val creating by vm.workspaceCreating.collectAsStateWithLifecycle()
    val created by vm.workspaceCreated.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadWorkspaces() }
    LaunchedEffect(created, enabled) { if (enabled) created?.takeIf { it.owner == owner }?.let { select(it.workspace.ref); adding = false; name = ""; vm.consumeWorkspaceCreated(it) } }
    val current = workspaces.firstOrNull { it.ref == selected }?.name ?: selected
    if (!enabled) SettingsGroup("工作区") {
        Text(current, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
    } else {
        SettingsGroup("工作区") {
            workspaces.forEachIndexed { index, workspace ->
                if (index > 0) GroupDivider()
                ChoiceRow(workspace.name, selected == workspace.ref, { select(workspace.ref) }, enabled = !creating)
            }
            if (workspaces.isNotEmpty()) GroupDivider()
            SettingsAction(if (adding) "收起新建工作区" else "新建工作区", enabled = !creating) { adding = !adding }
            if (adding) {
                SettingsField(name, { name = it }, "工作区名称", enabled = !creating)
                Text("在应用本机目录中创建独立文件夹。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsAction("创建工作区", enabled = !creating && name.isNotBlank() && name.length <= 80) { vm.createWorkspace(name, owner) }
            }
            if (creating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            GroupDivider()
            SettingsAction("刷新工作区", enabled = !creating, onClick = vm::loadWorkspaces)
        }
        if (workspaces.none { it.ref == selected }) SettingsCaption("当前工作区尚不可用，请刷新或选择其他工作区")
        error?.let { SettingsCaption(it, error = true) }
    }
}
@Composable internal fun AgentConfigMenu(expanded: Boolean, dismiss: () -> Unit, c: Conversation, vm: ConversationViewModel, anchor: IntRect = IntRect.Zero) {
    if (!expanded) return
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by rememberSaveable(c.id.value) { mutableStateOf(c.config.agent) }
    var model by rememberSaveable(c.id.value) { mutableStateOf(c.config.model) }
    var reasoning by rememberSaveable(c.id.value) { mutableStateOf(c.config.reasoning) }
    var workspace by rememberSaveable(c.id.value) { mutableStateOf(c.config.workspace) }
    val workspaceOwner = rememberSaveable(c.id.value) { java.util.UUID.randomUUID().toString() }
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val error by vm.workspaceError.collectAsStateWithLifecycle()
    val creating by vm.workspaceCreating.collectAsStateWithLifecycle()
    val created by vm.workspaceCreated.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val canMove = !c.hasTurns && c.draft.attachments.isEmpty() && c.draft.pendingAttachment == null
    val option = agents.firstOrNull { it.agent == agent }
    val levels = option?.models?.get(model).orEmpty()
    LaunchedEffect(Unit) { vm.loadWorkspaces() }
    LaunchedEffect(created, canMove) { if (canMove) created?.takeIf { it.owner == workspaceOwner }?.let { workspace = it.workspace.ref; adding = false; name = ""; vm.consumeWorkspaceCreated(it) } }
    FrostedMenu(true, dismiss, anchor) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            MenuSection("Agent") {
                AgentId.values().forEach { value -> MenuOption(value.label(), agent == value) { agent = value; model = profiles.firstOrNull { p -> p.agent == value }?.model.orEmpty(); reasoning = null } }
            }
            MenuSection("模型") {
                if (option?.models.isNullOrEmpty()) MenuCaption("尚未配置模型，请前往网关设置。")
                option?.models?.keys?.forEach { item -> MenuOption(item, model == item) { model = item; reasoning = null } }
            }
            MenuSection("思考程度") {
                if (levels.isEmpty()) MenuCaption("当前模型未开放调整")
                else {
                    MenuOption("默认", reasoning == null) { reasoning = null }
                    levels.forEach { level -> MenuOption(level, reasoning == level) { reasoning = level } }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = menuInk().copy(alpha = 0.08f))
            MenuSection("工作区") {
                if (!canMove) MenuCaption(if (c.hasTurns) "已有任务记录，执行工作区保持不变。" else "请先移除草稿附件，再切换工作区。")
                workspaces.forEach { item -> MenuOption(item.name, workspace == item.ref, enabled = canMove && !creating) { workspace = item.ref } }
                if (workspaces.none { it.ref == workspace }) MenuCaption("当前工作区尚不可用，请刷新或选择其他工作区")
                if (canMove) {
                    MenuAction("新建工作区", !creating) { adding = !adding }
                    if (adding) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), label = { Text("工作区名称") }, singleLine = true, enabled = !creating)
                        MenuCaption("在应用本机目录中创建独立文件夹。")
                        MenuAction("创建工作区", !creating && name.isNotBlank() && name.length <= 80) { vm.createWorkspace(name, workspaceOwner) }
                    }
                    if (creating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                }
                error?.let { MenuCaption(it) }
                MenuAction("刷新工作区") { vm.loadWorkspaces() }
            }
            MenuCaption("变更只影响下一轮，当前执行保持原配置。")
            if (agent != c.config.agent && c.hasTurns)
                MenuCaption("仍在当前对话中继续。Codex、Claude Code 与 OpenCode 的会话不能互相沿用：各自第一次运行时创建，之后在本对话里复用。回到原来的 Agent 会恢复它自己的会话。")
            if (agent != c.config.agent && c.draft.capabilities.any { !it.startsWith("plugin:") })
                MenuCaption("所选技能与 Agent 绑定，切换后请重新选择。插件选择会保留。")
        }
        Button(
            onClick = {
                val p = profiles.firstOrNull { it.agent == agent }
                vm.enqueue { vm.actions.configure(c.id, NextTurnConfig(agent, model, reasoning, workspace, p?.id ?: agent.gatewayProfileId(), p?.version ?: 0)) }
                dismiss()
            },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = menuAccent(), contentColor = MobbyColors.onAccent),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        ) { Text("应用", fontWeight = FontWeight.SemiBold) }
    }
}
@Composable internal fun ConfigDialog(vm: ConversationViewModel, c: Conversation?, onDismiss: () -> Unit, onApply: (NextTurnConfig, String?) -> Unit, anchor: IntRect = IntRect.Zero) {
    val state by vm.state.collectAsStateWithLifecycle()
    var project by rememberSaveable { mutableStateOf(c?.project) }
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by rememberSaveable { mutableStateOf(c?.config?.agent ?: AgentId.CODEX) }
    var model by rememberSaveable { mutableStateOf(c?.config?.model.orEmpty()) }
    var reasoning by rememberSaveable { mutableStateOf(c?.config?.reasoning) }
    var workspace by rememberSaveable { mutableStateOf(state.projects.firstOrNull { it.name == project }?.defaultWorkspace ?: c?.config?.workspace ?: "default") }
    val workspaceOwner = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val error by vm.workspaceError.collectAsStateWithLifecycle()
    val creating by vm.workspaceCreating.collectAsStateWithLifecycle()
    val created by vm.workspaceCreated.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val option = agents.firstOrNull { it.agent == agent }
    val levels = option?.models?.get(model).orEmpty()
    val canCreate = !creating && workspaces.any { it.ref == workspace } && (project == null || state.projects.any { it.name == project })
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() }; vm.loadWorkspaces() }
    LaunchedEffect(created) { created?.takeIf { it.owner == workspaceOwner }?.let { workspace = it.workspace.ref; adding = false; name = ""; vm.consumeWorkspaceCreated(it) } }
    FrostedMenu(true, onDismiss, anchor) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text("新建对话", Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = menuInk(), fontWeight = FontWeight.Medium)
            MenuSection("项目") {
                MenuOption("无项目", project == null, enabled = !creating) { project = null; workspace = c?.config?.workspace ?: "default" }
                state.projects.forEach { item -> MenuOption(item.name, project == item.name, enabled = !creating) { project = item.name; workspace = item.defaultWorkspace } }
                if (state.projects.isEmpty()) MenuCaption("可在会话抽屉的项目管理中新建项目。")
            }
            MenuSection("Agent") {
                AgentId.values().forEach { value -> MenuOption(value.label(), agent == value) { agent = value; model = profiles.firstOrNull { p -> p.agent == value }?.model.orEmpty(); reasoning = null } }
            }
            MenuSection("模型") {
                if (option?.models.isNullOrEmpty()) MenuCaption("尚未配置模型，请前往网关设置。")
                option?.models?.keys?.forEach { item -> MenuOption(item, model == item) { model = item; reasoning = null } }
            }
            MenuSection("思考程度") {
                if (levels.isEmpty()) MenuCaption("当前模型未开放调整")
                else {
                    MenuOption("默认", reasoning == null) { reasoning = null }
                    levels.forEach { level -> MenuOption(level, reasoning == level) { reasoning = level } }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = menuInk().copy(alpha = 0.08f))
            MenuSection("工作区") {
                workspaces.forEach { item -> MenuOption(item.name, workspace == item.ref, enabled = !creating) { workspace = item.ref } }
                if (workspaces.none { it.ref == workspace }) MenuCaption("当前工作区尚不可用，请刷新或选择其他工作区")
                MenuAction("新建工作区", !creating) { adding = !adding }
                if (adding) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), label = { Text("工作区名称") }, singleLine = true, enabled = !creating)
                    MenuCaption("在应用本机目录中创建独立文件夹。")
                    MenuAction("创建工作区", !creating && name.isNotBlank() && name.length <= 80) { vm.createWorkspace(name, workspaceOwner) }
                }
                if (creating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                error?.let { MenuCaption(it) }
                MenuAction("刷新工作区") { vm.loadWorkspaces() }
            }
        }
        Button(
            onClick = {
                val p = profiles.firstOrNull { it.agent == agent }
                onApply(NextTurnConfig(agent, model.ifBlank { p?.model.orEmpty() }, reasoning, workspace, p?.id ?: agent.gatewayProfileId(), p?.version ?: 0), project)
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp).heightIn(min = 48.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = menuAccent(), contentColor = MobbyColors.onAccent, disabledContainerColor = menuAccent().copy(alpha = 0.38f), disabledContentColor = MobbyColors.onAccentDisabled),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        ) { Text("创建", fontWeight = FontWeight.SemiBold) }
    }
}
@Composable internal fun PageHeader(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) {
        ActionIcon("返回", back, AppIcons.Back)
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 48.dp), style = MaterialTheme.typography.titleMedium)
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}
@Composable internal fun SettingsPage(system: SystemStatus, appearance: Appearance, setAppearance: (Appearance) -> Unit, navigate: (String) -> Unit, back: () -> Unit, vm: ConversationViewModel) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                SettingsItem("网关设置", { navigate("gateway") }, "模型地址与密钥")
                GroupDivider()
                SettingsItem("存储与保留", { navigate("history-limits") }, "会话与附件保留期限")
            }
            SettingsGroup("运行环境") {
                SettingsItem("Shell 诊断", { navigate("diagnostic") })
                GroupDivider()
                SettingsAction("重新检查运行环境") { vm.enqueue { vm.report(vm.actions.initialize()) } }
            }
            SettingsCaption(system.message)
            SettingsGroup("外观") {
                listOf(Appearance.SYSTEM to "跟随系统", Appearance.DARK to "深色", Appearance.LIGHT to "浅色").forEachIndexed { index, (key, label) ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(label, appearance == key, { setAppearance(key) })
                }
            }
            SettingsGroup {
                SettingsItem("已归档与最近删除", { navigate("archived") })
            }
        }
    }
}
@Composable internal fun GatewayPage(vm: ConversationViewModel, back: () -> Unit) {
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    GatewayForm(profiles, vm::enqueue, vm.actions::saveGateway, vm::refresh, back, vm.actions::checkGateway)
}

@Composable internal fun GatewayForm(profiles: List<GatewayProfile>, submit: (suspend () -> Unit) -> Unit,
    save: suspend (GatewayEdit) -> OperationResult, refresh: suspend () -> Unit, back: () -> Unit,
    check: suspend (GatewayProfile) -> DataResult<GatewayCheckReport>) {
    var agent by rememberSaveable { mutableStateOf(AgentId.CODEX) }
    var providerId by remember { mutableStateOf(GatewayProviders.CUSTOM) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("RESPONSES") }
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
    val profile = profiles.firstOrNull { it.agent == agent }
    val nativeProtocol = if (agent == AgentId.CLAUDE_CODE) "MESSAGES" else "RESPONSES"
    val nativeLabel = if (agent == AgentId.CLAUDE_CODE) "Messages" else "Responses"
    val protocolSupported = protocol == nativeProtocol
    var connectionNotice by remember(profile, endpoint, model, protocol, keyEdited) { mutableStateOf("") }
    LaunchedEffect(agent) { notice = "" }
    LaunchedEffect(agent, profile) {
        endpoint = profile?.endpoint.orEmpty()
        providerId = GatewayProviders.match(agent, endpoint)
        model = profile?.model.orEmpty()
        protocol = profile?.protocol ?: if (agent == AgentId.CLAUDE_CODE) "MESSAGES" else "RESPONSES"
        key = ""; keyEdited = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("网关设置", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                AgentId.values().forEachIndexed { index, value ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(value.label(), agent == value, { agent = value }, enabled = !busy)
                }
            }
            SettingsCaption("${agent.label()} 使用 $nativeLabel，通过本地桥接连接网关。")
            val providers = GatewayProviders.forAgent(agent)
            SettingsGroup("服务") {
                providers.forEachIndexed { index, provider ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(provider.label, providerId == provider.id, {
                        if (providerId != provider.id) {
                            providerId = provider.id
                            endpoint = provider.endpoint
                            notice = ""
                        }
                    }, enabled = !busy)
                }
                GroupDivider()
                ChoiceRow("自定义", providerId == GatewayProviders.CUSTOM, {
                    if (providerId != GatewayProviders.CUSTOM) {
                        if (GatewayProviders.matches(agent, endpoint)) endpoint = ""
                        providerId = GatewayProviders.CUSTOM
                        notice = ""
                    }
                }, enabled = !busy)
            }
            SettingsCaption("切换服务只更新地址，请确认模型和密钥仍属于该服务。Google Gemini 目前没有 Responses 或 Messages 接口，不能直接选择。")
            if (!protocolSupported) {
                SettingsCaption("当前保存的协议不适用于此 Agent；暂不提供协议转换。", error = true)
                SettingsGroup { SettingsAction("改用 $nativeLabel", enabled = !busy) { protocol = nativeProtocol; notice = "" } }
            }
            val stored = profile?.hasCredential == true
            SettingsGroup {
                SettingsField(endpoint, { if (providerId == GatewayProviders.CUSTOM) { endpoint = it; notice = "" } }, "网关地址", enabled = !busy && providerId == GatewayProviders.CUSTOM)
                GroupDivider()
                SettingsField(model, { model = it; notice = "" }, "模型名称", enabled = !busy)
                GroupDivider()
                SettingsField(key, { key = it; keyEdited = true; notice = "" }, if (stored && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）", enabled = !busy, visualTransformation = PasswordVisualTransformation())
                if (stored) {
                    GroupDivider()
                    SettingsAction("移除已保存密钥", enabled = !busy) { key = ""; keyEdited = true; notice = "" }
                }
            }
            SettingsCaption("凭据加密保存在设备；保存成功不代表连通性验证通过。")
            if (endpoint.trim().startsWith("http://", ignoreCase = true)) SettingsCaption("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", error = true)
            SettingsGroup {
                SettingsAction("保存当前配置", enabled = !busy && protocolSupported) {
                    val edit = GatewayEdit(agent, endpoint.trim(), model.trim(), protocol, if (keyEdited) key.toCharArray() else null)
                    saving = true; notice = ""
                    submit {
                        try {
                            when (val result = save(edit)) {
                                OperationResult.Done -> { notice = "配置已保存，尚未测试连接"; key = ""; keyEdited = false; refresh() }
                                is OperationResult.Failed -> notice = result.message
                            }
                        } finally { edit.credential?.fill('\u0000'); saving = false }
                    }
                }
            }
            if (notice.isNotBlank()) SettingsCaption(notice, error = notice.contains("失败") || notice.contains("不正确") || notice.contains("无效") || notice.contains("未确认"))
            SettingsCaption("测试连接会用已保存配置发送一个小型模型请求，可能产生少量费用；不验证 CLI、工具或会话恢复。")
            val matchesSaved = protocolSupported && profile != null && profile.endpoint.isNotBlank() && profile.model.isNotBlank() && !keyEdited &&
                endpoint.trim() == profile.endpoint && model.trim() == profile.model && protocol == profile.protocol
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
@Composable internal fun DiagnosticPage(vm: ConversationViewModel, back: () -> Unit) {
    val state by vm.diagnostic.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Shell 诊断", back)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCaption("状态：${state.phase.label()}")
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = cardColor()) {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                    if (state.lines.isEmpty()) item { EmptyPlaceholder("还没有输出", "输入命令后点执行") }
                    items(state.lines.size) { index -> androidx.compose.foundation.text.selection.SelectionContainer { Text(state.lines[index], fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            SettingsGroup { SettingsField(command, { command = it }, "输入 Shell 命令", singleLine = false, maxLines = 5) }
            SettingsGroup {
                SettingsAction("执行", enabled = command.isNotBlank()) { val captured = command; vm.enqueue { vm.report(vm.actions.shell(captured)) } }
                GroupDivider()
                SettingsAction("停止") { vm.enqueue { vm.report(vm.actions.stopShell()) } }
            }
        }
    }
}
@Composable internal fun ArchivedPage(state: InteractionState, vm: ConversationViewModel, back: () -> Unit) {
    val rows = state.conversations.filter { it.conversation.archived || it.conversation.deleted }
    Column(Modifier.fillMaxSize()) {
        PageHeader("已归档与最近删除", back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (rows.isEmpty()) item { EmptyPlaceholder("暂无归档或已删除对话", "归档或删除的会话会出现在这里") }
            else item {
                SettingsGroup {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.conversation.title, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { vm.enqueue { vm.report(if (row.conversation.deleted) vm.actions.delete(row.conversation.id, false) else vm.actions.archive(row.conversation.id, false)) } },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("恢复", style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }
}
private val pluginCatalogTabs = listOf("手机", "沟通", "文件")

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PluginPage(vm: ConversationViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val conversation = state.selected?.conversation
    val catalogue by vm.plugins.collectAsStateWithLifecycle()
    val error by vm.pluginsError.collectAsStateWithLifecycle()
    val loading by vm.pluginsLoading.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { pluginCatalogTabs.size })
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    var rationale by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.loadPlugins() }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching { DeviceStorage.persist(context, uri) }
        vm.loadPlugins()
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
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.loadPlugins() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("插件", onBack)
        rationale?.let { (explanation, needed) ->
            AlertDialog(onDismissRequest = { rationale = null }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("允许此插件使用手机能力") }, text = { Text(explanation) },
                confirmButton = { TextButton(onClick = { val request = needed; rationale = null; permissions.launch(request.toTypedArray()) }) { Text("继续") } },
                dismissButton = { TextButton(onClick = { rationale = null }) { Text("取消") } })
        }
        CatalogTabs(pluginCatalogTabs, pagerState.currentPage) { scope.launch { pagerState.animateScrollToPage(it) } }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::loadPlugins) { Text("重试") } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pluginCatalogTabs[it] }) { page ->
            val tab = pluginCatalogTabs[page]
            val visible = catalogue.filter { it.category == tab }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty() && !loading && error == null) item {
                    EmptyPlaceholder(
                        if (catalogue.isEmpty()) "当前没有可调用的插件" else "这个分类还没有插件",
                        if (catalogue.isEmpty()) "应用提供的插件就绪后会显示在这里" else "其他分类里有已提供的插件",
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
                                !plugin.available -> "开启"
                                chosen -> "移除"
                                else -> "使用"
                            },
                            actionEnabled = !plugin.available || conversation != null,
                            onAction = {
                                if (!plugin.available) requestAccess(plugin)
                                else if (conversation != null) vm.enqueue { vm.report(vm.actions.setPlugin(conversation.id, plugin, !chosen)) }
                            },
                        )
                        plugin.grant?.let { grant ->
                            if (chosen || !grant.available) TextButton(onClick = {
                                if (!grant.available) requestAccess(plugin, grant = true)
                                else if (conversation != null) vm.enqueue { vm.report(vm.actions.setPluginGrant(conversation.id, plugin, !grantChosen)) }
                            }, enabled = grant.available || grant.permissions.isNotEmpty()) { Text(when {
                                !grant.available -> "开启${grant.label}"
                                grantChosen -> "关闭${grant.label}"
                                else -> "允许${grant.label}"
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
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("在聊天中查找") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("查找消息") }, singleLine = true)
            if (query.isBlank()) EmptyPlaceholder("输入关键词查找", "只会搜索当前对话里的消息")
            else if (results.isEmpty()) EmptyPlaceholder("没有匹配的消息", "换个词再试")
            else Text("${results.size} 条匹配消息，点击可定位")
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(results, key = { it.targetKey }) { hit ->
                    TextButton(onClick = { onSelect(hit) }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (hit.messageId == null) "你" else detail.conversation.config.agent.label(), style = MaterialTheme.typography.labelSmall)
                            Text(hit.text, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
@Composable internal fun ShareDialog(detail: ConversationDetail, share: (String) -> Unit, onDismiss: () -> Unit) {
    val messages = detail.turns.flatMap { turn -> listOf("user:${turn.id.value}" to turn.userText) + turn.messages.map { "${turn.id.value}:${it.id}" to it.text } }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("选择分享消息") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { Text("默认不包含运行日志和配置", style = MaterialTheme.typography.bodySmall) }
            items(messages, key = { it.first }) { (id, text) ->
                ChoiceRow(text.take(200), id in selected, { selected = if (id in selected) selected - id else selected + id })
            }
        }
    }, confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { share(messages.filter { it.first in selected }.joinToString("\n\n") { it.second }); onDismiss() }) { Text("系统分享") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun HistoryDialog(id: ConversationId, vm: ConversationViewModel, dismiss: () -> Unit, content: @Composable (ConversationDetail) -> Unit) {
    var result by remember(id) { mutableStateOf<DataResult<ConversationDetail>?>(null) }
    LaunchedEffect(id) {
        result = try { DataResult.Loaded(vm.actions.history(id)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { DataResult.Failed("读取完整会话失败，请关闭后重试；已有记录保留") }
    }
    when (val value = result) {
        is DataResult.Loaded -> content(value.value)
        else -> AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("读取会话记录") }, text = {
            if (value is DataResult.Failed) Text(value.message) else CircularProgressIndicator()
        }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
    }
}
