package com.mobby.interaction.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobby.interaction.domain.*

@Composable internal fun AttachmentList(refs: List<String>, workspace: String, vm: ConversationViewModel, remove: ((String) -> Unit)? = null) {
    refs.forEach { ref -> key(ref) {
        var result by remember(ref, workspace) { mutableStateOf<DataResult<Attachment>?>(null) }
        LaunchedEffect(ref, workspace) { result = vm.actions.attachment(workspace, ref) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (val value = result) {
                is DataResult.Loaded -> "${value.value.name} · ${value.value.sizeBytes} B · 已就绪"
                is DataResult.Failed -> value.message
                null -> "正在读取附件信息…"
            }, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (remove != null) TextButton(onClick = { remove(ref) }) { Text("移除") }
        }
    } }
}
