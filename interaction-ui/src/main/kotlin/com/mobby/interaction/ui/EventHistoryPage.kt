package com.mobby.interaction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobby.interaction.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun EventHistoryPage(load: suspend () -> DataResult<EventHistoryLimits>, save: suspend (EventHistoryLimits) -> OperationResult, back: () -> Unit) {
    var days by rememberSaveable { mutableStateOf("") }
    var mib by rememberSaveable { mutableStateOf("") }
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
                is DataResult.Loaded -> { if (!initialized) { days = result.value.days.toString(); mib = result.value.mib.toString(); initialized = true }; loaded = true }
                is DataResult.Failed -> message = result.message
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "无法读取日志设置，请重试" }
    }
    val valid = days.toIntOrNull()?.let { it in 1..3650 } == true && mib.toIntOrNull()?.let { it in 1..1024 } == true
    Column(Modifier.fillMaxSize()) {
        PageHeader("运行日志保留", back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅清理已结束任务的旧事件记录，保留对话、文件和当前任务。此上限不包含输出文件及附件。")
            OutlinedTextField(days, { days = it; message = null }, label = { Text("保留天数（1–3650）") }, enabled = loaded && !busy, singleLine = true)
            OutlinedTextField(mib, { mib = it; message = null }, label = { Text("事件内容上限（MiB，1–1024）") }, enabled = loaded && !busy, singleLine = true)
            if (!loaded && message == null) Text("正在读取设置…")
            message?.let { Text(it) }
            if (!loaded && message != null) OutlinedButton(onClick = { retry++ }) { Text("重试读取") }
            Button(enabled = loaded && valid && !busy, onClick = {
                val value = EventHistoryLimits(days.toInt(), mib.toInt())
                busy = true; message = null
                scope.launch {
                    try {
                        message = when (val result = save(value)) {
                            OperationResult.Done -> "已保存，将在下次任务结束或启动时清理"
                            is OperationResult.Failed -> result.message
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "保存结果未确认，请重试" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在保存…" else "保存日志设置") }
        }
    }
}
