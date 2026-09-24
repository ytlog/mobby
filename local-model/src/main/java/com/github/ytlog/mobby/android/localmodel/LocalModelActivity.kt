package com.github.ytlog.mobby.android.localmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** Module-owned UI: all service operations below go through the same HTTP API as external clients. */
class LocalModelActivity : ComponentActivity() {
    private fun label(zh: String, en: String): String = if (resources.configuration.locales[0].language == "zh") zh else en
    private val auth by lazy { LocalModelAuth(this) }
    private val json = Json { ignoreUnknownKeys = true }
    private var status by mutableStateOf("Stopped")
    private var error by mutableStateOf<String?>(null)
    private var family by mutableStateOf("Qwen")
    private var candidates by mutableStateOf<List<Candidate>>(emptyList())
    private var installed by mutableStateOf<List<InstalledModel>>(emptyList())
    private var progress by mutableStateOf<InstallProgress?>(null)
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
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
            if (c.responseCode !in 200..299) error(text.take(300))
            text
        } finally { c.disconnect() }
    }

    private fun action(block: suspend () -> Unit) = lifecycleScope.launch {
        error = null
        try { block() } catch (e: Exception) { error = e.message?.take(300) ?: "Operation failed" }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        val health = request("/local/v1/health")
        status = json.parseToJsonElement(health).jsonObject["status"]?.jsonPrimitive?.content ?: "Unknown"
        installed = json.decodeFromString(request("/local/v1/models"))
    }

    private suspend fun catalog() = withContext(Dispatchers.IO) {
        candidates = json.decodeFromString(request("/local/v1/catalog/models?family=$family"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action { runCatching { refresh() }.onFailure { status = "Stopped" } }
        setContent {
            MaterialTheme {
                val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label("本地模型服务", "Local model service"), style = MaterialTheme.typography.headlineSmall)
                    Text("${label("状态", "Status")}: $status · http://127.0.0.1:11435/v1")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            auth.token("admin"); auth.token("inference")
                            startForegroundService(Intent(this@LocalModelActivity, LocalModelService::class.java))
                            action { repeat(20) { delay(250); if (runCatching { refresh() }.isSuccess) return@action }; error("Service did not become ready") }
                        }) { Text(label("启动", "Start")) }
                        OutlinedButton(onClick = { action { withContext(Dispatchers.IO) { request("/local/v1/server/stop", "POST", "{}") }; status = "Stopping" } }) { Text(label("停止", "Stop")) }
                        OutlinedButton(onClick = { action { refresh() } }) { Text(label("刷新", "Refresh")) }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Local model key", auth.token("inference"))) }) { Text(label("复制推理密钥", "Copy inference key")) }
                    Text(label("可将 http://127.0.0.1:11435/v1 配为自定义网关；先启动服务并加载模型。", "Use http://127.0.0.1:11435/v1 as a custom gateway after starting the service and loading a model."))
                    HorizontalDivider()
                    Text(label("已下载模型", "Installed models"), style = MaterialTheme.typography.titleMedium)
                    installed.forEach { model ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(model.id, Modifier.weight(1f))
                            Button(onClick = { action { withContext(Dispatchers.IO) { request("/local/v1/loads", "POST", """{"model":"${model.id}"}""") }; refresh() } }) { Text(label("加载", "Load")) }
                        }
                    }
                    HorizontalDivider()
                    Text(label("下载小模型（GGUF）", "Download small GGUF models"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Qwen", "Gemma").forEach { name ->
                            FilterChip(selected = family == name, onClick = { family = name; candidates = emptyList(); action { catalog() } }, label = { Text(name) })
                        }
                        Button(onClick = { action { catalog() } }) { Text(label("搜索", "Search")) }
                    }
                    Text(label("从 Hugging Face 获取列表，仅显示有 SHA-256 校验信息的 GGUF 文件。", "Results come from Hugging Face and include only GGUF files with SHA-256 metadata."), style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { item ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.path, style = MaterialTheme.typography.titleSmall)
                            Text("${item.repo} · ${item.size / 1_048_576} MiB")
                            Button(enabled = installed.none { it.id == item.id } && progress?.status !in listOf("queued", "downloading"), onClick = { action {
                                val response = withContext(Dispatchers.IO) { request("/local/v1/installs", "POST", json.encodeToString(item)) }
                                activeOperation = json.parseToJsonElement(response).jsonObject["operationId"]?.jsonPrimitive?.content
                                progress = InstallProgress(activeOperation ?: "", "queued", 0, item.size)
                                while (activeOperation != null) {
                                    delay(800)
                                    progress = withContext(Dispatchers.IO) { json.decodeFromString(request("/local/v1/operations/${activeOperation}")) }
                                    if (progress?.status in listOf("installed", "failed")) { activeOperation = null; refresh() }
                                }
                            } }) { Text(label("下载", "Download")) }
                        } }
                    }
                    progress?.let { Text("${label("下载", "Download")}: ${it.status} ${it.received / 1_048_576}/${it.total / 1_048_576} MiB ${it.error.orEmpty()}") }
                    HorizontalDivider()
                    Text(label("MLC：当前构建未打包 Android 运行时和模型编译库，因此暂不开放 MLC 下载。", "MLC runtime and compiled model libraries are not packaged; MLC downloads are disabled."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
