package com.github.ytlog.mobby.android.deviceoperation

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.domain.ConversationDetail
import com.github.ytlog.mobby.android.conversation.domain.ExecutionId
import com.github.ytlog.mobby.android.conversation.domain.OperationResult
import com.github.ytlog.mobby.android.conversation.ui.ConversationViewModel
import com.github.ytlog.mobby.android.conversation.ui.TextButton
import com.github.ytlog.mobby.android.conversation.ui.capabilityLabel
import com.github.ytlog.mobby.android.conversation.ui.raisedColor
import com.github.ytlog.mobby.android.conversation.ui.screenAccessSettingsIntent
import com.github.ytlog.mobby.android.device.DeviceAccess
import com.github.ytlog.mobby.android.device.DeviceCatalog
import com.github.ytlog.mobby.android.device.DeviceHost
import com.github.ytlog.mobby.android.deviceoperation.ui.DeviceLabels
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperation
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperationResponse
import java.util.UUID

/** The OS grant and the conversation grant are separate. Only the runtime can record the latter. */
@Composable
internal fun devicePermissionPrompt(operation: DeviceOperation, vm: ConversationViewModel, respond: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val spec = DeviceCatalog.all.first { it.id == operation.plugin }
    val ref = DeviceCatalog.actionRef(operation.plugin, operation.action)
    val latestResponse by rememberUpdatedState(respond)
    var pending by rememberSaveable(operation.operationId, operation.revision) { mutableStateOf(false) }
    fun finish() {
        if (!pending) return
        latestResponse(if (DeviceHost.granted(context, ref)) "confirm" else "cancel")
    }
    if (LocalActivityResultRegistryOwner.current == null) return { response ->
        if (!pending) {
            if (response != "confirm" || DeviceHost.granted(context, ref)) {
                pending = true
                latestResponse(response)
            } else vm.enqueue { vm.report(OperationResult.Failed(DeviceLabels.text("请打开 mobby 完成系统授权", "Open mobby to grant system access"))) }
        }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.enqueue {
            if (uri != null) vm.report(vm.actions.saveDeviceDirectory(uri.toString()))
            finish()
        }
    }
    return { response ->
        if (!pending) {
            pending = true
            if (response != "confirm" || DeviceHost.granted(context, ref)) latestResponse(response)
            else {
                when {
                    ref == spec.grantRef -> runtime.launch(checkNotNull(spec.grant).permissions.toTypedArray())
                    spec.access == DeviceAccess.RUNTIME -> runtime.launch(
                        (if (spec.id == "media") DeviceHost.mediaPermissions() else spec.permissions).toTypedArray())
                    spec.access == DeviceAccess.ACCESSIBILITY -> settings.launch(screenAccessSettingsIntent(context))
                    spec.access == DeviceAccess.DOCUMENT_TREE -> directory.launch(null)
                    else -> finish()
                }
            }
        }
    }
}


internal fun ConversationViewModel.respondToOperation(execution: ExecutionId, operation: DeviceOperation, response: String) {
    val attention = operation.requiresAttention ?: return
    enqueue {
        report(actions.respondToDevice(DeviceOperationResponse(UUID.randomUUID().toString(), execution.value,
            operation.operationId, attention.promptId, operation.revision, response)))
    }
}

@Composable
internal fun ConversationDevicePermissionDialog(detail: ConversationDetail, vm: ConversationViewModel) {
    val turn = detail.turns.lastOrNull { it.occupied } ?: return
    val execution = turn.execution ?: return
    val operation = turn.deviceOperations.firstOrNull { it.operation.requiresAttention?.reasonCode == "device.authorize" }
        ?.operation ?: return
    key(operation.operationId) {
        val respond = devicePermissionPrompt(operation, vm) { vm.respondToOperation(execution, operation, it) }
        val capability = capabilityLabel(DeviceCatalog.actionRef(operation.plugin, operation.action))
        val action = DeviceLabels.title(operation.plugin, operation.action)
        AlertDialog(
            onDismissRequest = { respond("cancel") },
            containerColor = raisedColor(),
            shape = RoundedCornerShape(24.dp),
            title = { Text(DeviceLabels.text("允许使用「$capability」？", "Allow $capability?")) },
            text = { Text(DeviceLabels.text(
                "这条会话请求执行「$action」。授权后可继续使用，点输入框上方标签的 × 可撤销。",
                "This conversation requests $action. Allow access to continue. Remove its chip above the composer to revoke access.",
            )) },
            confirmButton = { TextButton(onClick = { respond("confirm") }) { Text(DeviceLabels.text("授权并继续", "Allow and continue")) } },
            dismissButton = { TextButton(onClick = { respond("cancel") }) { Text(DeviceLabels.text("拒绝", "Deny")) } },
        )
    }
}
