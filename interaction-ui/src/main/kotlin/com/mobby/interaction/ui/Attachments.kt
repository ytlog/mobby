package com.mobby.interaction.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobby.interaction.domain.*
import kotlinx.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
private val previewDecodeDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable internal fun AttachmentList(refs: List<String>, workspace: String, vm: ConversationViewModel, remove: ((String) -> Unit)? = null) {
    refs.forEach { ref -> key(ref, workspace) {
        var result by remember(ref, workspace) { mutableStateOf<DataResult<Attachment>?>(null) }
        LaunchedEffect(ref, workspace) { result = vm.actions.attachment(workspace, ref) }
        when (val value = result) {
            is DataResult.Loaded -> AttachmentItem(value.value, { expanded -> vm.actions.previewAttachment(workspace, ref, expanded) }, remove?.let { { it(ref) } })
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (value is DataResult.Failed) value.message else "正在读取附件信息…", Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (remove != null) TextButton(onClick = { remove(ref) }) { Text("移除") }
            }
        }
    } }
}

/** Shared by drafts, frozen user turns and the conversation attachment list. */
@Composable internal fun AttachmentItem(attachment: Attachment, loadPreview: suspend (Boolean) -> DataResult<AttachmentPreview>, remove: (() -> Unit)? = null) {
    var expanded by remember(attachment.ref) { mutableStateOf(false) }
    val image = attachment.mediaType.startsWith("image/")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (image) AttachmentImage(attachment.ref, attachment.name, false, loadPreview, Modifier.size(80.dp).clickable(role = Role.Button, onClickLabel = "查看图片") { expanded = true })
        Column(Modifier.weight(1f)) {
            Text("${attachment.name} · ${attachment.sizeBytes} B · 已就绪", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (image) TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "查看图片 ${attachment.name}" }) { Text("查看图片") }
        }
        if (remove != null) TextButton(onClick = remove, modifier = Modifier.semantics { contentDescription = "移除附件 ${attachment.name}" }) { Text("移除") }
    }
    if (expanded && image) AlertDialog(
        onDismissRequest = { expanded = false },
        title = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { AttachmentImage(attachment.ref, attachment.name, true, loadPreview, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭预览") } }
    )
}

@Composable internal fun AttachmentImage(ref: String, name: String, expanded: Boolean, load: suspend (Boolean) -> DataResult<AttachmentPreview>, modifier: Modifier, onReady: (Boolean) -> Unit = {}) {
    var bitmap by remember(ref, expanded) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(ref, expanded) { mutableStateOf<String?>(null) }
    var retry by remember(ref, expanded) { mutableIntStateOf(0) }
    LaunchedEffect(ref, expanded, retry) {
        bitmap = null; error = null; onReady(false)
        try {
            when (val result = load(expanded)) {
                is DataResult.Failed -> error = result.message
                is DataResult.Loaded -> bitmap = withContext(previewDecodeDispatcher) {
                    requireNotNull(BitmapFactory.decodeByteArray(result.value.bytes, 0, result.value.bytes.size)).asImageBitmap()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "图片预览读取失败" }
        onReady(bitmap != null)
    }
    val ready = bitmap
    if (ready != null) Image(ready, "$name${if (expanded) "预览" else "缩略图"}", modifier, contentScale = ContentScale.Fit)
    else Column(modifier) {
        if (error == null) Text("正在加载图片…", style = MaterialTheme.typography.bodySmall)
        else {
            Text("预览失败", style = MaterialTheme.typography.bodySmall)
            if (expanded) Text(error!!, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { retry++ }) { Text("重试预览") }
        }
    }
}
