package com.mobby.interaction.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*

@Composable internal fun TextEditDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { save(value) }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
@Composable private fun ConfigurationFields(agent: AgentId, model: String, reasoning: String?, options: List<AgentOption>,
    setAgent: (AgentId) -> Unit, setModel: (String) -> Unit, setReasoning: (String?) -> Unit) {
    Text("Agent", style = MaterialTheme.typography.labelLarge)
    AgentId.values().forEach { value -> Row {
        RadioButton(agent == value, { setAgent(value) }); TextButton(onClick = { setAgent(value) }) { Text(value.label()) }
    } }
    val option = options.firstOrNull { it.agent == agent }
    Text("下一轮模型", style = MaterialTheme.typography.labelLarge)
    if (option?.models.isNullOrEmpty()) Text("尚未配置模型，请前往网关设置。", style = MaterialTheme.typography.bodySmall)
    option?.models?.keys?.forEach { name -> Row { RadioButton(model == name, { setModel(name) }); TextButton(onClick = { setModel(name) }) { Text(name) } } }
    val levels = option?.models?.get(model).orEmpty()
    if (levels.isNotEmpty()) {
        Text("思考程度", style = MaterialTheme.typography.labelLarge)
        Row { RadioButton(reasoning == null, { setReasoning(null) }); TextButton(onClick = { setReasoning(null) }) { Text("默认") } }
        levels.forEach { level -> Row { RadioButton(reasoning == level, { setReasoning(level) }); TextButton(onClick = { setReasoning(level) }) { Text(level) } } }
    } else Text("思考程度：当前能力接口未开放调整", style = MaterialTheme.typography.bodySmall)
    Text("工作区：默认本机工作区", style = MaterialTheme.typography.bodySmall)
}
@Composable internal fun AgentConfigMenu(expanded: Boolean, dismiss: () -> Unit, c: Conversation, vm: ConversationViewModel) {
    if (!expanded) return
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by remember { mutableStateOf(c.config.agent) }
    var model by remember { mutableStateOf(c.config.model) }
    var reasoning by remember { mutableStateOf(c.config.reasoning) }
    DropdownMenu(true, dismiss, modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.padding(16.dp)) {
            ConfigurationFields(agent, model, reasoning, agents, { agent = it; model = profiles.firstOrNull { p -> p.agent == it }?.model.orEmpty(); reasoning = null }, { model = it; reasoning = null }, { reasoning = it })
            Text("变更只影响下一轮，当前执行保持原配置。", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = dismiss) { Text("取消") }
                Button(onClick = {
                    val p = profiles.firstOrNull { it.agent == agent }
                    vm.enqueue { vm.actions.configure(c.id, NextTurnConfig(agent, model, reasoning, c.config.workspace, p?.id ?: if (agent == AgentId.CODEX) "CODEX" else "CLAUDE", p?.version ?: 0)) }
                    dismiss()
                }) { Text(if (c.hasTurns && c.config.agent != agent) "新建并使用此配置" else "应用") }
            }
        }
    }
}
@Composable internal fun ConfigDialog(vm: ConversationViewModel, c: Conversation?, onDismiss: () -> Unit, onApply: (NextTurnConfig) -> Unit) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val profiles by vm.gateways.collectAsStateWithLifecycle()
    var agent by remember { mutableStateOf(c?.config?.agent ?: AgentId.CODEX) }
    var model by remember { mutableStateOf(c?.config?.model.orEmpty()) }
    var reasoning by remember { mutableStateOf(c?.config?.reasoning) }
    LaunchedEffect(Unit) { vm.enqueue { vm.refresh() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("新建对话") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ConfigurationFields(agent, model, reasoning, agents, { agent = it; model = profiles.firstOrNull { p -> p.agent == it }?.model.orEmpty(); reasoning = null }, { model = it; reasoning = null }, { reasoning = it })
        }
    }, confirmButton = { TextButton(onClick = { val p = profiles.firstOrNull { it.agent == agent }; onApply(NextTurnConfig(agent, model.ifBlank { p?.model.orEmpty() }, reasoning, "default", p?.id ?: if (agent == AgentId.CODEX) "CODEX" else "CLAUDE", p?.version ?: 0)) }) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable internal fun PageHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        ActionIcon("返回", back, Icons.Outlined.ArrowBack); Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable internal fun SettingsPage(system: SystemStatus, appearance: Appearance, setAppearance: (Appearance) -> Unit, navigate: (String) -> Unit, back: () -> Unit, vm: ConversationViewModel) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { navigate("gateway") }) { Text("网关设置") }
            Text("运行环境：${system.message}")
            OutlinedButton(onClick = { vm.enqueue { vm.report(vm.actions.initialize()) } }) { Text("重新检查运行环境") }
            TextButton(onClick = { navigate("diagnostic") }) { Text("Shell 诊断") }
            Text("外观", style = MaterialTheme.typography.titleMedium)
            listOf(Appearance.SYSTEM to "跟随系统", Appearance.DARK to "深色", Appearance.LIGHT to "浅色").forEach { (key, label) ->
                Row { RadioButton(appearance == key, { setAppearance(key) }); TextButton(onClick = { setAppearance(key) }) { Text(label) } }
            }
            TextButton(onClick = { navigate("archived") }) { Text("已归档与最近删除") }
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
            AgentId.values().forEach { value -> Row { RadioButton(agent == value, { agent = value }, enabled = !busy); TextButton(enabled = !busy, onClick = { agent = value }) { Text(value.label()) } } }
            listOf("RESPONSES" to "Responses", "MESSAGES" to "Messages", "CHAT" to "Chat Completions").forEach { (value, label) -> Row { RadioButton(protocol == value, { protocol = value; notice = "" }, enabled = !busy); TextButton(enabled = !busy, onClick = { protocol = value; notice = "" }) { Text(label) } } }
            OutlinedTextField(endpoint, { endpoint = it; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("网关地址") }, singleLine = true)
            OutlinedTextField(model, { model = it; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("模型名称") }, singleLine = true)
            val stored = profile?.hasCredential == true
            OutlinedTextField(key, { key = it; keyEdited = true; notice = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(if (stored && !keyEdited) "已保存密钥，输入可替换" else "API Key（无鉴权可留空）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (stored) TextButton(enabled = !busy, onClick = { key = ""; keyEdited = true; notice = "" }) { Text("移除已保存密钥") }
            Text("凭据加密保存在设备；保存成功不代表连通性验证通过。", style = MaterialTheme.typography.bodySmall)
            if (endpoint.trim().startsWith("http://", ignoreCase = true)) Text("HTTP 会明文传输密钥和内容，仅用于可信网络；建议使用 HTTPS。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = {
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
            val matchesSaved = profile != null && profile.endpoint.isNotBlank() && profile.model.isNotBlank() && !keyEdited &&
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
            items(state.conversations.filter { it.conversation.archived || it.conversation.deleted }, key = { it.conversation.id.value }) { row ->
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(row.conversation.title, Modifier.weight(1f))
                    TextButton(onClick = { vm.enqueue { vm.report(if (row.conversation.deleted) vm.actions.delete(row.conversation.id, false) else vm.actions.archive(row.conversation.id, false)) } }) { Text("恢复") }
                }
            }
        }
    }
}
@Composable internal fun PluginPage(onBack: () -> Unit) {
    Column {
        PageHeader("插件", onBack)
        Text("当前没有应用提供且可调用的插件。", Modifier.padding(24.dp))
    }
}
@Composable internal fun FindDialog(detail: ConversationDetail, onDismiss: () -> Unit, onSelect: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(detail, query) { ConversationSearch.find(detail, query) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("在聊天中查找") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("查找消息") }, singleLine = true)
            Text("${results.size} 条匹配消息，点击可定位")
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择分享消息") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { Text("默认不包含运行日志和配置", style = MaterialTheme.typography.bodySmall) }
            items(messages, key = { it.first }) { (id, text) -> Row { Checkbox(id in selected, { selected = if (it) selected + id else selected - id }); Text(text.take(200), Modifier.weight(1f)) } }
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
        else -> AlertDialog(onDismissRequest = dismiss, title = { Text("读取会话记录") }, text = {
            if (value is DataResult.Failed) Text(value.message) else CircularProgressIndicator()
        }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
    }
}
