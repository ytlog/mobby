package com.github.ytlog.mobby.android.deviceoperation

import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.ytlog.mobby.android.conversation.domain.ConversationDetail
import com.github.ytlog.mobby.android.conversation.domain.ExecutionId
import com.github.ytlog.mobby.android.conversation.domain.OperationResult
import com.github.ytlog.mobby.android.conversation.ui.AlertDialog
import com.github.ytlog.mobby.android.conversation.ui.ConversationViewModel
import com.github.ytlog.mobby.android.conversation.ui.ScreenUi
import com.github.ytlog.mobby.android.conversation.ui.TextButton
import com.github.ytlog.mobby.android.conversation.ui.UiStrings
import com.github.ytlog.mobby.android.conversation.ui.capabilityLabel
import com.github.ytlog.mobby.android.conversation.ui.raisedColor
import com.github.ytlog.mobby.android.conversation.ui.screenAccessSettingsIntent
import com.github.ytlog.mobby.android.conversation.ui.screenSetupIntent
import com.github.ytlog.mobby.android.conversation.ui.shouldRecommendAdb
import com.github.ytlog.mobby.android.device.DeviceAccess
import com.github.ytlog.mobby.android.device.DeviceCatalog
import com.github.ytlog.mobby.android.device.DeviceHost
import com.github.ytlog.mobby.android.device.PrivateScreenInput
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
    val screen = if (spec.id == "screen") ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value else null
    val recommendAdb = shouldRecommendAdb(screen)
    val latestResponse by rememberUpdatedState(respond)
    var pending by rememberSaveable(operation.operationId, operation.revision) { mutableStateOf(false) }
    fun finish() {
        if (!pending) return
        latestResponse(if (DeviceHost.granted(context, ref)) "confirm" else "cancel")
    }
    if (LocalActivityResultRegistryOwner.current == null) return { response ->
        if (!pending) {
            if (response != "confirm" || !recommendAdb && DeviceHost.granted(context, ref)) {
                pending = true
                latestResponse(response)
            } else vm.enqueue { vm.report(OperationResult.Failed(DeviceLabels.text("请打开 mobby 完成系统授权", "Open mobby to grant system access"))) }
        }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    val screenSetup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (pending) {
            if (result.resultCode == Activity.RESULT_OK) finish() else latestResponse("cancel")
        }
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.enqueue {
            if (uri != null) vm.report(vm.actions.saveDeviceDirectory(uri.toString()))
            finish()
        }
    }
    return { response ->
        if (!pending) {
            pending = true
            if (response == "confirm" && recommendAdb) screenSetup.launch(screenSetupIntent(context))
            else if (response != "confirm" || DeviceHost.granted(context, ref)) latestResponse(response)
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
    val privateInput = turn.deviceOperations.firstOrNull {
        it.operation.requiresAttention?.reasonCode == "screen.private_input"
    }?.operation
    if (privateInput != null) {
        key(privateInput.operationId) { PrivateScreenInputDialog(privateInput) }
        return
    }
    val execution = turn.execution ?: return
    val operation = turn.deviceOperations.firstOrNull { it.operation.requiresAttention?.reasonCode == "device.authorize" }
        ?.operation ?: return
    key(operation.operationId) {
        val respond = devicePermissionPrompt(operation, vm) { vm.respondToOperation(execution, operation, it) }
        val capability = capabilityLabel(DeviceCatalog.actionRef(operation.plugin, operation.action))
        val action = DeviceLabels.title(operation.plugin, operation.action)
        val screen = ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value
        val recommendAdb = operation.plugin == "screen" && shouldRecommendAdb(screen)
        AlertDialog(
            onDismissRequest = { respond("cancel") },
            title = { Text(DeviceLabels.text("允许使用「$capability」？", "Allow $capability?")) },
            text = {
                val request = DeviceLabels.text(
                    "这条会话请求执行「$action」。授权后可继续使用，点输入框上方标签的 × 可撤销。",
                    "This conversation requests $action. Allow access to continue. Remove its chip above the composer to revoke access.",
                )
                Text(if (recommendAdb) "$request\n\n${UiStrings.adbScreenRecommendation}" else request)
            },
            confirmButton = { TextButton(onClick = { respond("confirm") }) {
                Text(if (recommendAdb) UiStrings.setUpRecommendedScreen else DeviceLabels.text("授权并继续", "Allow and continue"))
            } },
            dismissButton = { TextButton(onClick = { respond("cancel") }) { Text(DeviceLabels.text("拒绝", "Deny")) } },
        )
    }
}


@Composable
private fun PrivateScreenInputDialog(operation: DeviceOperation) {
    val prompt = operation.requiresAttention ?: return
    val labels = remember(prompt.promptId) { PrivateScreenInput.labels(operation.operationId, prompt.promptId) } ?: return
    val values = remember(prompt.promptId) { mutableStateListOf<String>().apply { addAll(List(labels.size) { "" }) } }
    var submitted by remember(prompt.promptId) { mutableStateOf(false) }
    fun respond(text: List<String>?) {
        if (submitted) return
        submitted = PrivateScreenInput.submit(operation.operationId, prompt.promptId, text)
        if (submitted) values.indices.forEach { values[it] = "" }
    }
    AlertDialog(
        onDismissRequest = { respond(null) },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(DeviceLabels.text("请填写副屏字段", "Fill the field on the secondary display")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { if (it.length <= 2000) values[index] = it },
                        label = { Text(label.ifBlank { DeviceLabels.text("字段 ${index + 1}", "Field ${index + 1}") }) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitted,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { respond(values.toList()) }, enabled = !submitted && values.any(String::isNotEmpty)) {
                Text(DeviceLabels.text("填入副屏", "Fill field"))
            }
        },
        dismissButton = {
            TextButton(onClick = { respond(null) }, enabled = !submitted) {
                Text(DeviceLabels.text("取消", "Cancel"))
            }
        },
    )
}
