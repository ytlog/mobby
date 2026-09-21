package com.mobby.interaction.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*

@Composable internal fun TextEditDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value) }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable private fun ConfigurationFields(agent: AgentId, model: String, reasoning: String?, options: List<AgentOption>,
    setAgent: (AgentId) -> Unit, setModel: (String) -> Unit, setReasoning: (String?) -> Unit) {
    Text("Agent", style = MaterialTheme.typography.labelLarge)
    AgentId.values().forEach { value -> ChoiceRow(value.label(), agent == value, { setAgent(value) }) }
    val option = options.firstOrNull { it.agent == agent }
    Text("下一轮模型", style = MaterialTheme.typography.labelLarge)
    if (option?.models.isNullOrEmpty()) Text("尚未配置模型，请前往网关设置。", style = MaterialTheme.typography.bodySmall)
    option?.models?.keys?.forEach { name -> ChoiceRow(name, model == name, { setModel(name) }) }
    val levels = option?.models?.get(model).orEmpty()
    if (levels.isNotEmpty()) {
        Text("思考程度", style = MaterialTheme.typography.labelLarge)
        ChoiceRow("默认", reasoning == null, { setReasoning(null) })
        levels.forEach { level -> ChoiceRow(level, reasoning == level, { setReasoning(level) }) }
    } else Text("思考程度：当前能力接口未开放调整", style = MaterialTheme.typography.bodySmall)
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
    Text("工作区", style = MaterialTheme.typography.labelLarge)
    if (!enabled) Text(workspaces.firstOrNull { it.ref == selected }?.name ?: selected, style = MaterialTheme.typography.bodySmall)
    else {
        workspaces.forEach { workspace -> ChoiceRow(workspace.name, selected == workspace.ref, { select(workspace.ref) }, enabled = !creating) }
        if (workspaces.none { it.ref == selected }) Text("当前工作区尚不可用，请刷新或选择其他工作区", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { adding = !adding }, enabled = !creating) { Text("新建工作区") }
        if (adding) {
            OutlinedTextField(name, { name = it }, label = { Text("工作区名称") }, singleLine = true, enabled = !creating)
            Text("在应用本机目录中创建独立文件夹。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.createWorkspace(name, owner) }, enabled = !creating && name.isNotBlank() && name.length <= 80) { Text("创建工作区") }
        }
        if (creating) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = vm::loadWorkspaces) { Text("刷新工作区") }
}
@Composable internal fun AgentConfigMenu(expanded: Boolean, dismiss: () -> Unit, c: Conversation, vm: ConversationViewModel) {
    if (!expanded) return
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by rememberSaveable(c.id.value) { mutableStateOf(c.config.agent) }
    var model by rememberSaveable(c.id.value) { mutableStateOf(c.config.model) }
    var reasoning by rememberSaveable(c.id.value) { mutableStateOf(c.config.reasoning) }
    var workspace by rememberSaveable(c.id.value) { mutableStateOf(c.config.workspace) }
    val workspaceOwner = rememberSaveable(c.id.value) { java.util.UUID.randomUUID().toString() }
    RaisedDropdownMenu(true, dismiss, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.padding(16.dp)) {
            ConfigurationFields(agent, model, reasoning, agents, { agent = it; model = profiles.firstOrNull { p -> p.agent == it }?.model.orEmpty(); reasoning = null }, { model = it; reasoning = null }, { reasoning = it })
            val canMove = !c.hasTurns && c.draft.attachments.isEmpty() && c.draft.pendingAttachment == null
            WorkspacePicker(vm, workspace, workspaceOwner, canMove) { workspace = it }
            if (!canMove) Text(if (c.hasTurns) "已有任务记录，执行工作区保持不变。" else "请先移除草稿附件，再切换工作区。", style = MaterialTheme.typography.bodySmall)
            Text("变更只影响下一轮，当前执行保持原配置。", style = MaterialTheme.typography.bodySmall)
            if (agent != c.config.agent && c.draft.capabilities.any { !it.startsWith("plugin:") })
                Text("所选技能与 Agent 绑定，切换后请重新选择。插件选择会保留。", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = dismiss) { Text("取消") }
                Button(onClick = {
                    val p = profiles.firstOrNull { it.agent == agent }
                    vm.enqueue { vm.actions.configure(c.id, NextTurnConfig(agent, model, reasoning, workspace, p?.id ?: if (agent == AgentId.CODEX) "CODEX" else "CLAUDE", p?.version ?: 0)) }
                    dismiss()
                }) { Text(if (c.hasTurns && c.config.agent != agent) "新建并使用此配置" else "应用") }
            }
        }
    }
}
@Composable internal fun ConfigDialog(vm: ConversationViewModel, c: Conversation?, onDismiss: () -> Unit, onApply: (NextTurnConfig, String?) -> Unit) {
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
    val creating by vm.workspaceCreating.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp), title = { Text("新建对话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ProjectChoices(state.projects, project, !creating) { selected -> project = selected?.name; workspace = selected?.defaultWorkspace ?: c?.config?.workspace ?: "default" }
            ConfigurationFields(agent, model, reasoning, agents, { agent = it; model = profiles.firstOrNull { p -> p.agent == it }?.model.orEmpty(); reasoning = null }, { model = it; reasoning = null }, { reasoning = it })
            WorkspacePicker(vm, workspace, workspaceOwner) { workspace = it }
        }
    }, confirmButton = { TextButton(enabled = !creating && workspaces.any { it.ref == workspace } && (project == null || state.projects.any { it.name == project }), onClick = { val p = profiles.firstOrNull { it.agent == agent }; onApply(NextTurnConfig(agent, model.ifBlank { p?.model.orEmpty() }, reasoning, workspace, p?.id ?: if (agent == AgentId.CODEX) "CODEX" else "CLAUDE", p?.version ?: 0), project) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable internal fun PageHeader(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
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
                GroupDivider()
                SettingsItem("Shell 诊断", { navigate("diagnostic") })
            }
            Text(system.message, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup {
                TextButton(
                    onClick = { vm.enqueue { vm.report(vm.actions.initialize()) } },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text("重新检查运行环境", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge) }
            }
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
        model = profile?.model.orEmpty()
        protocol = profile?.protocol ?: if (agent == AgentId.CLAUDE_CODE) "MESSAGES" else "RESPONSES"
        key = ""; keyEdited = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("网关设置", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsGroup {
                AgentId.values().forEachIndexed { index, value ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(value.label(), agent == value, { agent = value }, enabled = !busy)
                }
            }
            Text("${agent.label()} 使用 $nativeLabel，通过本地桥接连接网关。")
            if (!protocolSupported) {
                Text("当前保存的协议不适用于此 Agent；暂不提供协议转换。", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { protocol = nativeProtocol; notice = "" }) { Text("改用 $nativeLabel") }
            }

            OutlinedTextField(endpoint, { endpoint = it; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("网关地址") }, singleLine = true)
            OutlinedTextField(model, { model = it; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("模型名称") }, singleLine = true)
            val stored = profile?.hasCredential == true
            OutlinedTextField(key, { key = it; keyEdited = true; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(if (stored && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (stored) TextButton(enabled = !busy, onClick = { key = ""; keyEdited = true; notice = "" }) { Text("移除已保存密钥") }
            Text("凭据加密保存在设备；保存成功不代表连通性验证通过。", style = MaterialTheme.typography.bodySmall)
            if (endpoint.trim().startsWith("http://", ignoreCase = true)) Text("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && protocolSupported, onClick = {
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
            }) { Text("保存当前配置") }
            if (notice.isNotBlank()) Text(notice)
            Text("测试连接会用已保存配置发送一个小型模型请求，可能产生少量费用；不验证 CLI、工具或会话恢复。", style = MaterialTheme.typography.bodySmall)
            val matchesSaved = protocolSupported && profile != null && profile.endpoint.isNotBlank() && profile.model.isNotBlank() && !keyEdited &&
                endpoint.trim() == profile.endpoint && model.trim() == profile.model && protocol == profile.protocol
            OutlinedButton(enabled = !busy && matchesSaved, onClick = {
                val target = profile ?: return@OutlinedButton
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
            }) { Text("测试已保存连接") }
            if (checking) TextButton(enabled = !cancelling, onClick = {
                cancelling = true; connectionNotice = "正在取消检查…"; checkJob?.cancel()
            }) { Text("取消检查") }
            if (!matchesSaved && !busy) Text("请先保存当前修改，再测试连接。", style = MaterialTheme.typography.bodySmall)
            if (connectionNotice.isNotBlank()) Text(connectionNotice)
        }
    }
}
@Composable internal fun DiagnosticPage(vm: ConversationViewModel, back: () -> Unit) {
    val state by vm.diagnostic.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Shell 诊断", back)
        Text("状态：${state.phase.label()}", Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            if (state.lines.isEmpty()) item { EmptyPlaceholder("还没有输出", "输入命令后点执行") }
            items(state.lines.size) { index -> androidx.compose.foundation.text.selection.SelectionContainer { Text(state.lines[index], fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        }
        OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("输入 Shell 命令") }, maxLines = 5)
        Row(Modifier.padding(12.dp)) {
            Button(onClick = { val captured = command; vm.enqueue { vm.report(vm.actions.shell(captured)) } }, enabled = command.isNotBlank()) { Text("执行") }
            TextButton(onClick = { vm.enqueue { vm.report(vm.actions.stopShell()) } }) { Text("停止") }
        }
    }
}
@Composable internal fun ArchivedPage(state: InteractionState, vm: ConversationViewModel, back: () -> Unit) {
    Column {
        PageHeader("已归档与最近删除", back)
        LazyColumn {
            val rows = state.conversations.filter { it.conversation.archived || it.conversation.deleted }
            if (rows.isEmpty()) item { EmptyPlaceholder("暂无归档或已删除对话", "归档或删除的会话会出现在这里") }
            items(rows, key = { it.conversation.id.value }) { row ->
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(row.conversation.title, Modifier.weight(1f))
                    TextButton(onClick = { vm.enqueue { vm.report(if (row.conversation.deleted) vm.actions.delete(row.conversation.id, false) else vm.actions.archive(row.conversation.id, false)) } }) { Text("恢复") }
                }
            }
        }
    }
}
@Composable internal fun PluginPage(vm: ConversationViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val conversation = state.selected?.conversation
    val catalogue by vm.plugins.collectAsStateWithLifecycle()
    val error by vm.pluginsError.collectAsStateWithLifecycle()
    val loading by vm.pluginsLoading.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.loadPlugins() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("插件", onBack)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("插件由本应用提供。开启系统授权并把插件加入本轮后，Agent 才能使用对应能力；未加入草稿时不会操作手机。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::loadPlugins) { Text("重试") } }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (catalogue.isEmpty() && !loading && error == null) item { EmptyPlaceholder("当前没有可调用的插件", "应用提供的插件就绪后会显示在这里") }
            items(catalogue, key = { it.ref }) { plugin ->
                val chosen = conversation?.draft?.capabilities?.contains(plugin.ref) == true
                Surface(shape = RoundedCornerShape(18.dp), color = raisedColor()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.Plugin, null, Modifier.size(26.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        !plugin.available -> plugin.unavailableReason ?: "插件不可用"
                                        chosen -> "已加入本轮草稿"
                                        else -> "已就绪，可加入本轮"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(plugin.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!plugin.available) OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }, modifier = Modifier.fillMaxWidth()) { Text("打开系统无障碍设置") }
                        Button(
                            onClick = { if (conversation != null) vm.enqueue { vm.report(vm.actions.setPlugin(conversation.id, plugin, !chosen)) } },
                            enabled = conversation != null && (chosen || plugin.available),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (chosen) "从本轮移除" else "加入本轮草稿") }
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
