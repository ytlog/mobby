package com.github.ytlog.mobby.android.deviceoperation

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.runtime.api.device.*
import com.github.ytlog.mobby.android.deviceoperation.ui.*
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.ui.ConversationViewModel
import com.github.ytlog.mobby.android.conversation.ui.LocalAttachmentPreviewHost
import com.github.ytlog.mobby.android.conversation.ui.ScreenUi
import com.github.ytlog.mobby.android.conversation.ui.screenSetupIntent

/** Composition root adapter: cards know neither ConversationViewModel nor the runtime client. */
@Composable internal fun ConversationDeviceCard(operation: DeviceOperation, turn: Turn, conversation: Conversation, vm: ConversationViewModel) {
    var preview by remember { mutableStateOf<String?>(null) }
    val execution = turn.execution
    val previewHost = LocalAttachmentPreviewHost.current
    val context = LocalContext.current
    val screen = ScreenUi.controller?.state?.collectAsState()?.value
    val reauthorize = operation.error?.code == DeviceErrorCode.ADB_AUTHORIZATION_REQUIRED &&
        screen?.mode == ScreenMode.ADB && !screen.connected
    val respond: ((String) -> Unit)? = execution?.takeIf { turn.occupied }?.let { id ->
        { response ->
            val prompt = operation.requiresAttention
            if (prompt?.reasonCode == "screen.private_input") {
                if (response == "cancel")
                    com.github.ytlog.mobby.android.device.PrivateScreenInput.submit(operation.operationId, prompt.promptId, null)
            } else vm.respondToOperation(id, operation, response)
        }
    }
    val permissionResponse = if (respond != null && operation.requiresAttention?.reasonCode == "device.authorize")
        devicePermissionPrompt(operation, vm, respond) else respond
    DeviceTaskCard(operation, DeviceCardActions(
        stop = execution?.takeIf { turn.occupied }?.let { id -> { vm.stop(id) } },
        openResource = { ref -> if (ref in operation.result?.resourceRefs.orEmpty()) {
            if (previewHost != null) previewHost(ref) else preview = ref
        } },
        respond = permissionResponse,
        reauthorizeAdb = if (reauthorize) ({ context.startActivity(screenSetupIntent(context)) }) else null,
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
