package com.github.ytlog.mobby.android.localmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.github.ytlog.mobby.android.localization.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/** The page uses the public HTTP management API for every model operation. */
class LocalModelActivity : ComponentActivity() {
    companion object { const val EXTRA_DARK = "local_model_dark"; private const val BASE_URL = "http://127.0.0.1:11435/v1" }
    private fun label(zh: String, en: String) = if (AppLanguage.current == AppLanguage.CHINESE) zh else en
    private val auth by lazy { LocalModelAuth(this) }
    private val sourcePreference by lazy { ModelDownloadSourcePreference(this) }
    private val json = Json { ignoreUnknownKeys = true }
    private var ready by mutableStateOf(false)
    private var starting by mutableStateOf(true)
    private var listing by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var family by mutableStateOf("Qwen")
    private var source by mutableStateOf(ModelDownloadSource.defaultFor(AppLanguage.current))
    private var candidates by mutableStateOf<List<Candidate>>(emptyList())
    private var installed by mutableStateOf<List<InstalledModel>>(emptyList())
    private var loaded by mutableStateOf<String?>(null)
    private var progress by mutableStateOf<InstallProgress?>(null)
    private var showConnectionDetails by mutableStateOf(false)
    private var activeOperation: String? = null

    private fun request(path: String, method: String = "GET", body: String? = null): String {
        val c = URL("http://127.0.0.1:11435$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = 60_000; c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer ${auth.token("admin")}")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
            if (c.responseCode !in 200..299) {
                val message = runCatching { json.parseToJsonElement(response).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                error((message ?: response).take(300))
            }
            response
        } finally { c.disconnect() }
    }

    private fun action(block: suspend () -> Unit) = lifecycleScope.launch {
        error = null
        try { block() } catch (e: Exception) { error = e.message?.take(300) ?: label("操作失败", "Operation failed") }
    }

    private suspend fun refresh() {
        val health = withContext(Dispatchers.IO) { json.parseToJsonElement(request("/local/v1/health")).jsonObject }
        ready = health["status"]?.jsonPrimitive?.content == "LISTENING"
        loaded = health["loadedModel"]?.jsonPrimitive?.contentOrNull
        installed = withContext(Dispatchers.IO) { json.decodeFromString(request("/local/v1/models")) }
    }

    private suspend fun listModels() {
        if (!ready) return
        listing = true
        try {
            val selected = family
            val selectedSource = source.id
            candidates = withContext(Dispatchers.IO) { json.decodeFromString(request("/local/v1/catalog/models?backend=llama&family=$selected&source=$selectedSource")) }
        } finally { listing = false }
    }

    private suspend fun startService() {
        starting = true
        try {
            withContext(Dispatchers.IO) { auth.token("admin"); auth.token("inference") }
            startForegroundService(Intent(this, LocalModelService::class.java))
            var connected = false
            for (attempt in 0 until 24) {
                delay(250)
                if (runCatching { refresh() }.isSuccess && ready) { connected = true; break }
            }
            check(connected) { label("服务启动失败，请重试", "Service did not start; please retry") }
            starting = false
            val operations = withContext(Dispatchers.IO) { json.decodeFromString<List<InstallProgress>>(request("/local/v1/operations")) }
            operations.lastOrNull { it.status == "queued" || it.status == "downloading" }?.let { action { monitor(it.id) } }
            listModels()
        } finally { starting = false }
    }

    private suspend fun monitor(id: String) {
        activeOperation = id
        while (activeOperation == id) {
            progress = withContext(Dispatchers.IO) { json.decodeFromString(request("/local/v1/operations/$id")) }
            if (progress?.status in listOf("installed", "failed")) {
                activeOperation = null
                refresh()
                if (progress?.status == "failed") error = progress?.error
                break
            }
            delay(800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        val systemBar = if (dark) 0xFF111213.toInt() else 0xFFFAFAFA.toInt()
        enableEdgeToEdge(statusBarStyle = if (dark) SystemBarStyle.dark(systemBar) else SystemBarStyle.light(systemBar, systemBar),
            navigationBarStyle = if (dark) SystemBarStyle.dark(systemBar) else SystemBarStyle.light(systemBar, systemBar))
        source = sourcePreference.selected(AppLanguage.current)
        action { startService() }
        setContent {
            LocalModelTheme(dark) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                            IconButton(onClick = ::finish, modifier = Modifier.align(Alignment.CenterStart)) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                            Text(label("本地模型服务", "Local model service"), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(label("下载并加载模型后，会自动出现在网关中。停止模型或服务后，临时网关会移除。", "Downloaded and loaded models appear in gateways automatically. The temporary gateway disappears when the model or service stops."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label("目前仅支持纯文本；Agent 工具调用和图片暂不支持。", "Text only for now; agent tools and images are not supported."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ModelSection(label("服务", "Service")) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(if (ready) label("运行中", "Running") else if (starting) label("正在启动…", "Starting…") else label("未运行", "Stopped"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                        Text(label("仅本机可访问", "Available on this device"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (starting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else if (!ready) TextButton(onClick = { action { startService() } }) { Text(label("重试", "Retry")) }
                                }
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            if (ready) {
                                if (installed.isNotEmpty()) ModelSection(label("已下载 · 点击加载", "Downloaded · tap to load")) {
                                    installed.forEachIndexed { index, model ->
                                        if (index > 0) ModelDivider()
                                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f)) {
                                                Text(model.displayName.ifBlank { model.id }, style = MaterialTheme.typography.bodyLarge)
                                                Text("${model.quantization} · ${model.size / 1_048_576} MiB" + if (loaded == model.id) label(" · 已加入网关", " · in gateways") else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (loaded == model.id) OutlinedButton(onClick = { action {
                                                withContext(Dispatchers.IO) { request("/local/v1/models/unload", "POST", "{}") }
                                                refresh()
                                            } }) { Text(label("停止模型", "Unload")) }
                                            else OutlinedButton(onClick = { action {
                                                withContext(Dispatchers.IO) { request("/local/v1/loads", "POST", json.encodeToString(mapOf("model" to model.id))) }
                                                refresh()
                                            } }) { Text(label("加载", "Load")) }
                                        }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(label("可下载模型", "Models to download"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        listOf("Qwen", "Gemma").forEach { name ->
                                            ModelChip(if (name == "Qwen") label("千问", "Qwen") else "Gemma", family == name, !listing) { family = name; candidates = emptyList(); action { listModels() } }
                                        }
                                        if (listing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                    Text(label("下载来源", "Download source"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ModelDownloadSource.values().forEach { option ->
                                            ModelChip(when (option) {
                                                ModelDownloadSource.MODELSCOPE -> label("魔搭", "ModelScope")
                                                ModelDownloadSource.HUGGING_FACE -> "Hugging Face"
                                                ModelDownloadSource.HF_MIRROR -> label("HF 镜像", "HF mirror")
                                            }, source == option, !listing) { action {
                                                check(withContext(Dispatchers.IO) { sourcePreference.select(option) }) { label("保存下载来源失败", "Could not save download source") }
                                                source = option; candidates = emptyList(); listModels()
                                            } }
                                        }
                                    }
                                    Text(label("手动选择会保存；列表与下载都使用所选来源。文件会经过 SHA-256 校验。", "Your choice is saved; listing and downloads use that source. Files are SHA-256 verified."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (candidates.isEmpty() && !listing) Text(label("暂无模型，请检查网络后重试。", "No models found. Check your connection and retry."), style = MaterialTheme.typography.bodySmall)
                                    if (candidates.isNotEmpty()) ModelSection(null) { candidates.forEachIndexed { index, item ->
                                        if (index > 0) ModelDivider()
                                        val existing = installed.any { it.id == item.id }
                                        val downloading = progress?.modelId == item.id && progress?.status in listOf("queued", "downloading")
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(item.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                                        Text("${item.quantization} · ${item.size / 1_048_576} MiB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (!downloading) OutlinedButton(enabled = !existing && activeOperation == null, onClick = { action {
                                                        val response = withContext(Dispatchers.IO) { request("/local/v1/installs", "POST", json.encodeToString(item)) }
                                                        val id = json.parseToJsonElement(response).jsonObject["operationId"]?.jsonPrimitive?.content ?: error("Missing operation ID")
                                                        progress = InstallProgress(id, "queued", 0, item.size, item.id)
                                                        monitor(id)
                                                    } }) { Text(if (existing) label("已下载", "Downloaded") else label("下载", "Download")) }
                                                }
                                                Text(item.repo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                if (downloading) {
                                                    val current = progress!!
                                                    LinearProgressIndicator(progress = (current.received.toFloat() / current.total.coerceAtLeast(1)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                                                    Text("${current.received / 1_048_576} / ${current.total / 1_048_576} MiB", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                    } }
                                    TextButton(onClick = { action { listModels() } }, enabled = !listing) { Text(label("刷新模型列表", "Refresh model list")) }
                                }
                                TextButton(onClick = { showConnectionDetails = !showConnectionDetails }) {
                                    Text(label("其他客户端接入", "Other client access"))
                                }
                                if (showConnectionDetails) ModelSection(null) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(label("本机外的客户端无法直接访问此地址。", "This address is available only on this device."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(BASE_URL, style = MaterialTheme.typography.bodyMedium)
                                        loaded?.let { Text("${label("模型 ID", "Model ID")}: $it", style = MaterialTheme.typography.bodySmall) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Local model URL", BASE_URL)) }) { Text(label("复制地址", "Copy URL")) }
                                            TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Local model key", auth.inferenceToken())) }) { Text(label("复制密钥", "Copy key")) }
                                        }
                                        loaded?.let { id -> TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Local model ID", id)) }) { Text(label("复制模型 ID", "Copy model ID")) } }
                                    }
                                }
                                TextButton(onClick = { action {
                                    withContext(Dispatchers.IO) { request("/local/v1/server/stop", "POST", "{}") }
                                    ready = false; loaded = null
                                } }) { Text(label("停止本地服务", "Stop local service")) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ModelSection(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(title, Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dark = MaterialTheme.colorScheme.background == Color(0xFF121212)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = if (dark) Color(0xFF1E1E1E) else Color.White) { Column(content = content) }
    }
}

@Composable private fun ModelDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outline)
}

@Composable private fun ModelChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF121212)
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        color = if (selected) (if (dark) Color(0xFF292929) else Color.White) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)) else null,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
