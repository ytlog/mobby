package com.mobby.app

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
            AgentMode.values().forEach { mode ->
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
            label = { Text(if (state.mode == AgentMode.SHELL) "输入命令" else "输入任务") }, minLines = 2, maxLines = 5)
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
    val store = remember { GatewayStore(context) }
    var mode by remember { mutableStateOf(AgentMode.CLAUDE) }
    var config by remember { mutableStateOf(GatewayConfig(protocol = GatewayProtocol.MESSAGES)) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(mode) {
        runCatching { store.load(mode) }.onSuccess { config = it; error = ""; notice = "" }
            .onFailure { config = GatewayConfig(); error = "无法读取保存的配置，请重新填写" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("网关设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AgentMode.CLAUDE, AgentMode.CODEX).forEach { agent ->
                    FilterChip(selected = mode == agent, onClick = { mode = agent }, label = { Text(agent.label) })
                }
            }
            Text("两个 Agent 分别保存配置。填写后即可使用网关，无需官方账号登录。", style = MaterialTheme.typography.bodySmall)
            GatewayProtocol.values().forEach { protocol ->
                Row {
                    RadioButton(selected = config.protocol == protocol, onClick = { config = config.copy(protocol = protocol) })
                    TextButton(onClick = { config = config.copy(protocol = protocol) }) { Text(protocol.label) }
                }
            }
            OutlinedTextField(value = config.endpoint, onValueChange = { config = config.copy(endpoint = it.trim()) },
                label = { Text("网关地址") }, placeholder = { Text("https://gateway.example/v1") }, singleLine = true)
            Text("可填 API 基础地址或完整接口路径。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = config.model, onValueChange = { config = config.copy(model = it.trim()) }, label = { Text("模型名称") }, singleLine = true)
            OutlinedTextField(value = config.key, onValueChange = { config = config.copy(key = it.trim()) }, label = { Text("API Key（无鉴权可留空）") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (config.endpoint.startsWith("http://")) Text("当前使用 HTTP，密钥和内容会明文传输。", color = MaterialTheme.colorScheme.error)
            Text("密钥加密保存在本机。跨协议支持文本和工具调用；回复在网关生成完成后显示。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (notice.isNotEmpty()) Text(notice)
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { store.save(mode, config) }.onSuccess { error = ""; notice = "${mode.label} 已保存" }
                .onFailure { error = it.message ?: "保存失败"; notice = "" }
        }) { Text("保存当前配置") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
