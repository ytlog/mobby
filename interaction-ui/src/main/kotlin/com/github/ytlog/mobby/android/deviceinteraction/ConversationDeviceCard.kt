package com.github.ytlog.mobby.android.deviceinteraction

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.deviceinteraction.model.*
import com.github.ytlog.mobby.android.deviceinteraction.ui.*
import com.github.ytlog.mobby.android.interaction.domain.*
import com.github.ytlog.mobby.android.interaction.ui.ConversationViewModel
import com.github.ytlog.mobby.android.interaction.ui.LocalAttachmentPreviewHost
import java.util.UUID

/** Composition root adapter: cards know neither ConversationViewModel nor the runtime client. */
@Composable internal fun ConversationDeviceCard(operation: DeviceOperation, turn: Turn, conversation: Conversation, vm: ConversationViewModel) {
    var preview by remember { mutableStateOf<String?>(null) }
    val execution = turn.execution
    val previewHost = LocalAttachmentPreviewHost.current
    DeviceTaskCard(operation, DeviceCardActions(
        stop = execution?.takeIf { turn.occupied }?.let { id -> { vm.stop(id) } },
        openResource = { ref -> if (ref in operation.result?.resourceRefs.orEmpty()) {
            if (previewHost != null) previewHost(ref) else preview = ref
        } },
        respond = execution?.takeIf { turn.occupied }?.let { id -> { response ->
            operation.requiresAttention?.let { attention -> vm.enqueue {
                vm.report(vm.actions.respondToDevice(DeviceInteractionResponse(UUID.randomUUID().toString(), id.value,
                    operation.operationId, attention.interactionId, operation.revision, response)))
            } }
            Unit
        } },
    ), stopping = turn.phase == ExecutionPhase.CANCELLING, thumbnail = { ref ->
        val bytes by produceState<ByteArray?>(null, ref) {
            value = (vm.actions.previewAttachment(conversation.config.workspace, ref, false) as? DataResult.Loaded)?.value?.bytes
        }
        val bitmap = remember(bytes) { bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (bitmap != null) Image(bitmap.asImageBitmap(), DeviceLabels.text("附件预览", "Attachment preview"), Modifier.fillMaxWidth().heightIn(max = 180.dp))
    })
    preview?.let { ref -> DeviceResourceDialog(ref, load = {
        val metadata = vm.actions.attachment(conversation.config.workspace, ref)
        val bytes = vm.actions.previewAttachment(conversation.config.workspace, ref, true)
        if (metadata is DataResult.Loaded && bytes is DataResult.Loaded)
            DevicePreview(metadata.value.name, metadata.value.mediaType, bytes.value.bytes)
        else null
    }, onDismiss = { preview = null }) }
}
