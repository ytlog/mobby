package com.mobby.app

import com.mobby.runtime.api.*
import kotlinx.coroutines.launch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { TestConsoleScreen() } } }
    }
}

@Composable
fun TestConsoleScreen(vm: TestConsoleViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val runtime = state.runtime
    val list = rememberLazyListState()
    var followOutput by remember { mutableStateOf(true) }
    var gatewaySettings by remember { mutableStateOf(false) }
    if (gatewaySettings) GatewaySettings(onDismiss = { gatewaySettings = false })
    val dragging by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) followOutput = false }
    LaunchedEffect(list) {
        snapshotFlow { !list.isScrollInProgress && !list.canScrollForward }.collect { atBottom ->
            if (atBottom) followOutput = true
        }
    }
    LaunchedEffect(runtime.output.lastOrNull()?.id) {
        if (followOutput && runtime.output.isNotEmpty()) list.scrollToItem(runtime.output.lastIndex)
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("mobby · 运行测试", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { gatewaySettings = true }, enabled = !runtime.busy) { Text("网关设置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleMode.values().forEach { mode ->
                FilterChip(selected = state.mode == mode, onClick = { vm.mode(mode) }, label = { Text(mode.label) }, enabled = !runtime.busy)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("状态：${runtime.status}", modifier = Modifier.weight(1f))
            if ((!runtime.ready || !runtime.dependenciesReady) && !runtime.initializing && !runtime.busy) TextButton(onClick = vm::retry) { Text("重试初始化") }
        }
        if (runtime.initializing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (runtime.output.isEmpty()) item { Text("执行输出会显示在这里。", style = MaterialTheme.typography.bodySmall) }
                items(runtime.output, key = { it.id }) { item ->
                    SelectionContainer {
                        Text(item.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedTextField(value = state.input, onValueChange = vm::input, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.mode == ConsoleMode.SHELL) "输入命令" else "输入任务") }, minLines = 2, maxLines = 5)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { followOutput = true; vm.send() }, enabled = runtime.ready && !runtime.busy && state.input.isNotBlank()) { Text("发送") }
            OutlinedButton(onClick = vm::stop, enabled = runtime.busy) { Text("停止") }
            TextButton(onClick = vm::clear) { Text("清空输出") }
        }
    }
}

@Composable
private fun GatewaySettings(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val admin = (context.applicationContext as MobbyApplication).runtime.admin
    val scope = rememberCoroutineScope()
    var agent by remember { mutableStateOf(AgentId.CLAUDE_CODE) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(GatewayProtocol.MESSAGES) }
    var key by remember { mutableStateOf("") }
    var hasCredential by remember { mutableStateOf(false) }
    var keyEdited by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(agent) {
        when (val result = admin.listGatewayProfiles()) {
            is AdminResult.Success -> result.value.firstOrNull { it.agent == agent }?.let {
                endpoint = it.endpoint; model = it.model; protocol = it.protocol
                hasCredential = it.hasCredential; key = ""; keyEdited = false; notice = ""
            }
            is AdminResult.Failed -> notice = "无法读取网关配置"
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("网关设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentId.values().forEach { value ->
                    FilterChip(selected = value == agent, onClick = { agent = value }, enabled = !saving,
                        label = { Text(if (value == AgentId.CODEX) "Codex" else "Claude Code") })
                }
            }
            GatewayProtocol.values().forEach { value ->
                Row {
                    RadioButton(selected = protocol == value, onClick = { protocol = value })
                    TextButton(onClick = { protocol = value }) { Text(when (value) {
                        GatewayProtocol.CHAT -> "Chat Completions"
                        GatewayProtocol.RESPONSES -> "Responses"
                        GatewayProtocol.MESSAGES -> "Messages"
                    }) }
                }
            }
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it.trim() }, label = { Text("网关地址") }, singleLine = true)
            OutlinedTextField(value = model, onValueChange = { model = it.trim() }, label = { Text("模型名称") }, singleLine = true)
            OutlinedTextField(value = key, onValueChange = { key = it; keyEdited = true },
                label = { Text(if (hasCredential && !keyEdited) "已保存密钥（输入可替换）" else "API Key（无鉴权可留空）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (hasCredential) TextButton(onClick = { key = ""; keyEdited = true }) { Text("移除已保存密钥") }
            Text("密钥仅保存在本机加密存储。保存与连通性验证是不同操作。", style = MaterialTheme.typography.bodySmall)
            if (notice.isNotEmpty()) Text(notice)
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = {
            saving = true
            val request = SaveGatewayRequest(agent, endpoint, model, protocol,
                if (keyEdited) SecretInput(key.toCharArray()) else null)
            scope.launch {
                when (val result = admin.saveGatewayProfile(request)) {
                    is AdminResult.Success -> { notice = "配置已保存，尚未测试连接"; hasCredential = result.value.hasCredential; key = ""; keyEdited = false }
                    is AdminResult.Failed -> notice = "保存失败，请检查地址、模型和密钥格式"
                }
                saving = false
            }
        }) { Text("保存当前配置") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
