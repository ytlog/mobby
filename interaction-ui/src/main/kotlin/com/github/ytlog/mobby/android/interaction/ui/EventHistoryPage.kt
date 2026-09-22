package com.github.ytlog.mobby.android.interaction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun EventHistoryPage(load: suspend () -> DataResult<EventHistoryLimits>, save: suspend (EventHistoryLimits) -> OperationResult, back: () -> Unit) {
    var days by rememberSaveable { mutableStateOf("") }
    var mib by rememberSaveable { mutableStateOf("") }
    var outputDays by rememberSaveable { mutableStateOf("") }
    var outputMiB by rememberSaveable { mutableStateOf("") }
    var attachmentMiB by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(retry) {
        loaded = false; message = null
        try {
            when (val result = load()) {
                is DataResult.Loaded -> { if (!initialized) { days = result.value.days.toString(); mib = result.value.mib.toString(); outputDays = result.value.outputDays.toString(); outputMiB = result.value.outputMiB.toString(); attachmentMiB = result.value.attachmentMiB.toString(); initialized = true }; loaded = true }
                is DataResult.Failed -> message = result.message
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "无法读取存储设置，请重试" }
    }
    val valid = days.toIntOrNull()?.let { it in 1..3650 } == true && mib.toIntOrNull()?.let { it in 1..1024 } == true &&
        outputDays.toIntOrNull()?.let { it in 1..3650 } == true && outputMiB.toIntOrNull()?.let { it in 1..4096 } == true && attachmentMiB.toIntOrNull()?.let { it in 1..8192 } == true
    Column(Modifier.fillMaxSize()) {
        PageHeader("存储与保留", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按完成时间清理已结束任务的旧日志与原始输出，保留运行结果索引和当前任务。输出清理不可恢复；工作区文件不清理；日志、原始输出、缓存和附件分别计算容量。")
            Text("对话输出缓存另有 256 MiB 上限，按轮次创建顺序清理旧正文；用户输入、草稿和附件不计入此缓存上限。")
            OutlinedTextField(days, { days = it; message = null }, label = { Text("保留天数（1–3650）") }, enabled = loaded && !busy, singleLine = true)
            OutlinedTextField(mib, { mib = it; message = null }, label = { Text("事件内容上限（MiB，1–1024）") }, enabled = loaded && !busy, singleLine = true)
            OutlinedTextField(outputDays, { outputDays = it; message = null }, label = { Text("输出保留天数（1–3650）") }, enabled = loaded && !busy, singleLine = true)
            OutlinedTextField(outputMiB, { outputMiB = it; message = null }, label = { Text("原始输出上限（MiB，1–4096）") }, enabled = loaded && !busy, singleLine = true)
            OutlinedTextField(attachmentMiB, { attachmentMiB = it; message = null }, label = { Text("附件存储上限（MiB，1–8192）") }, enabled = loaded && !busy, singleLine = true)
            Text("附件达到上限后暂停导入新内容，不自动删除已有附件；降低上限不会删除文件，相同附件仍可复用。")
            if (!loaded && message == null) Text("正在读取设置…")
            message?.let { Text(it) }
            if (!loaded && message != null) OutlinedButton(onClick = { retry++ }) { Text("重试读取") }
            Button(enabled = loaded && valid && !busy, onClick = {
                val value = EventHistoryLimits(days.toInt(), mib.toInt(), outputDays.toInt(), outputMiB.toInt(), attachmentMiB.toInt())
                busy = true; message = null
                scope.launch {
                    try {
                        message = when (val result = save(value)) {
                            OperationResult.Done -> "已保存；日志与输出在后续维护时清理，附件上限在下次导入时生效"
                            is OperationResult.Failed -> result.message
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "保存结果未确认，请重试" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在保存…" else "保存存储设置") }
        }
    }
}
