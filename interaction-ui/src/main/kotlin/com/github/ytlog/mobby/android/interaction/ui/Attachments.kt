package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.github.ytlog.mobby.android.interaction.domain.*
import kotlinx.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
private val previewDecodeDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Hosts without an Activity can route full previews to the app instead of opening a Dialog. */
internal val LocalAttachmentPreviewHost = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable internal fun AttachmentList(refs: List<String>, workspace: String, vm: ConversationViewModel, remove: ((String) -> Unit)? = null) {
    refs.forEach { ref -> key(ref, workspace) {
        var result by remember(ref, workspace) { mutableStateOf<DataResult<Attachment>?>(null) }
        LaunchedEffect(ref, workspace) { result = vm.actions.attachment(workspace, ref) }
        when (val value = result) {
            is DataResult.Loaded -> AttachmentItem(value.value, { expanded -> vm.actions.previewAttachment(workspace, ref, expanded) }, remove?.let { { it(ref) } })
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (value is DataResult.Failed) value.message else AppStrings.readingAttachmentDetails, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (remove != null) TextButton(onClick = { remove(ref) }) { Text(AppStrings.remove) }
            }
        }
    } }
}

/** Shared by drafts, frozen user turns and the conversation attachment list. */
@Composable internal fun AttachmentItem(attachment: Attachment, loadPreview: suspend (Boolean) -> DataResult<AttachmentPreview>, remove: (() -> Unit)? = null) {
    var expanded by remember(attachment.ref) { mutableStateOf(false) }
    val previewHost = LocalAttachmentPreviewHost.current
    val openPreview = { if (previewHost != null) previewHost(attachment.ref) else expanded = true }
    val image = attachment.mediaType.startsWith("image/")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (image) AttachmentImage(attachment.ref, attachment.name, false, loadPreview, Modifier.size(80.dp).clickable(role = Role.Button, onClickLabel = AppStrings.viewImage, onClick = openPreview))
        Column(Modifier.weight(1f)) {
            Text(AppStrings.bReady(attachment.name, attachment.sizeBytes), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            if (image) TextButton(onClick = openPreview, modifier = Modifier.semantics { contentDescription = AppStrings.viewImage2(attachment.name) }) { Text(AppStrings.viewImage) }
        }
        if (remove != null) TextButton(onClick = remove, modifier = Modifier.semantics { contentDescription = AppStrings.removeAttachment(attachment.name) }) { Text(AppStrings.remove) }
    }
    if (expanded && image) AlertDialog(
        onDismissRequest = { expanded = false },
        containerColor = raisedColor(),
        shape = RoundedCornerShape(24.dp),
        title = { Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { AttachmentImage(attachment.ref, attachment.name, true, loadPreview, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(AppStrings.closePreview) } }
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
        catch (_: Exception) { error = AppStrings.couldNotReadImagePreview }
        onReady(bitmap != null)
    }
    val ready = bitmap
    if (ready != null) Image(ready, "$name${if (expanded) AppStrings.preview else AppStrings.thumbnail}", modifier, contentScale = ContentScale.Fit)
    else Column(modifier) {
        if (error == null) Text(AppStrings.loadingImage, style = MaterialTheme.typography.bodySmall)
        else {
            Text(AppStrings.previewFailed, style = MaterialTheme.typography.bodySmall)
            if (expanded) Text(error!!, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { retry++ }) { Text(AppStrings.retryPreview) }
        }
    }
}
