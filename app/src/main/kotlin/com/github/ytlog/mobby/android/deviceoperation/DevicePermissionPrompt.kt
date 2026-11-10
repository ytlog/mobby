package com.github.ytlog.mobby.android.deviceoperation

import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import com.github.ytlog.mobby.android.conversation.ui.AppIcon
import com.github.ytlog.mobby.android.conversation.ui.AppIcons
import com.github.ytlog.mobby.android.conversation.ui.toolCallInk
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
import com.github.ytlog.mobby.android.conversation.ui.ScreenAccessRouting
import com.github.ytlog.mobby.android.conversation.ui.ScreenUi
import com.github.ytlog.mobby.android.conversation.ui.TextButton
import com.github.ytlog.mobby.android.conversation.ui.UiStrings
import com.github.ytlog.mobby.android.conversation.ui.capabilityLabel
import com.github.ytlog.mobby.android.conversation.ui.raisedColor
import com.github.ytlog.mobby.android.device.DeviceAccess
import com.github.ytlog.mobby.android.device.DeviceCatalog
import com.github.ytlog.mobby.android.device.DeviceHost
import com.github.ytlog.mobby.android.device.PrivateScreenInput
import com.github.ytlog.mobby.android.deviceoperation.ui.DeviceLabels
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperation
import com.github.ytlog.mobby.android.runtime.api.device.DeviceOperationResponse
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
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
            if (response != "confirm" || DeviceHost.granted(context, ref) &&
                ScreenAccessRouting.authorizationIntent(context, ref, available = true) == null) {
                pending = true
                latestResponse(response)
            } else vm.enqueue { vm.report(OperationResult.Failed(DeviceLabels.text("请打开 mobby 完成系统授权", "Open mobby to grant system access"))) }
        }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
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
            val available = response == "confirm" && DeviceHost.granted(context, ref)
            val setup = if (response == "confirm") ScreenAccessRouting.authorizationIntent(context, ref, available) else null
            if (setup != null) screenSetup.launch(setup)
            else if (response != "confirm" || available) latestResponse(response)
            else {
                when {
                    ref == spec.grantRef -> runtime.launch(checkNotNull(spec.grant).permissions.toTypedArray())
                    spec.access == DeviceAccess.RUNTIME -> runtime.launch(
                        (if (spec.id == "media") DeviceHost.mediaPermissions() else spec.permissions).toTypedArray())
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
        it.operation.requiresAttention?.reasonCode in setOf("screen.private_input", "screen.handoff")
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
        val ref = DeviceCatalog.actionRef(operation.plugin, operation.action)
        val capability = capabilityLabel(ref)
        val action = DeviceLabels.title(operation.plugin, operation.action)
        val screen = ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value
        val recommendAdb = ref == ScreenAccessRouting.REF &&
            ScreenAccessRouting.recommendsAdb(screen)
        val selectedAdbNeedsSetup = ref == ScreenAccessRouting.REF && ref in detail.conversation.draft.capabilities &&
            screen?.mode == ScreenMode.ADB && !screen.connected
        val backendAction = if (selectedAdbNeedsSetup) {
            if (screen?.adbPaired == true) UiStrings.reconnectAdbScreen else UiStrings.reauthorizeAdb
        } else null
        AlertDialog(
            onDismissRequest = { respond("cancel") },
            title = { Text(backendAction ?: DeviceLabels.text("允许使用「$capability」？", "Allow $capability?")) },
            text = {
                val request = if (backendAction != null) UiStrings.screenSelectionNeedsAdb else DeviceLabels.text(
                    "这条会话请求执行「$action」。授权后可继续使用，点输入框上方标签的 × 可撤销。",
                    "This conversation requests $action. Allow access to continue. Remove its chip above the composer to revoke access.",
                )
                Text(if (recommendAdb) "$request\n\n${UiStrings.adbScreenRecommendation}" else request)
            },
            confirmButton = { TextButton(onClick = { respond("confirm") }) {
                Text(backendAction ?: if (recommendAdb) UiStrings.setUpRecommendedScreen else DeviceLabels.text("授权并继续", "Allow and continue"))
            } },
            dismissButton = { TextButton(onClick = { respond("cancel") }) {
                Text(if (backendAction != null) UiStrings.cancel else DeviceLabels.text("拒绝", "Deny"))
            } },
        )
    }
}


@Composable
private fun PrivateScreenInputDialog(operation: DeviceOperation) {
    val prompt = operation.requiresAttention ?: return
    val request = remember(prompt.promptId) { PrivateScreenInput.request(operation.operationId, prompt.promptId) } ?: return
    var takingOver by rememberSaveable(prompt.promptId) { mutableStateOf(false) }
    val screen = ScreenUi.controller?.state?.collectAsStateWithLifecycle()?.value
    if (takingOver || screen?.manualControlId != null) return
    PrivateScreenInputPrompt(request, canTakeOver = screen?.active == true,
        onFill = { PrivateScreenInput.submit(operation.operationId, prompt.promptId, it) },
        onTakeOver = { PrivateScreenInput.chooseTakeOver(operation.operationId, prompt.promptId).also { if (it) takingOver = true } },
        onCancel = { PrivateScreenInput.submit(operation.operationId, prompt.promptId, null) })
}

/**
 * The agent's request for the user, in the style of the authorization dialog: what to type into the display's field,
 * which the agent never reads, and a way into the takeover page for steps the user must tap themselves.
 */
@Composable
internal fun PrivateScreenInputPrompt(request: PrivateScreenInput.Request, canTakeOver: Boolean,
    onFill: (List<String>) -> Boolean, onTakeOver: () -> Boolean, onCancel: () -> Unit) {
    val fields = request.fields
    val values = remember(request) { mutableStateListOf<String>().apply { addAll(List(fields.size) { "" }) } }
    var submitted by remember(request) { mutableStateOf(false) }
    val muted = toolCallInk()
    fun finish(done: Boolean) {
        if (!done) return
        submitted = true
        values.indices.forEach { values[it] = "" }
    }
    val title = when {
        fields.isEmpty() -> UiStrings.virtualScreenOperateTitle
        fields.size > 1 -> UiStrings.virtualScreenInputFieldsTitle
        fields.single().label.isBlank() -> UiStrings.virtualScreenInputAnyTitle
        else -> UiStrings.virtualScreenInputTitle(fields.single().label)
    }
    val reason = request.reason.ifBlank { if (fields.isEmpty()) UiStrings.virtualScreenHandoffPrompt else "" }
    AlertDialog(
        onDismissRequest = { if (!submitted) { submitted = true; onCancel() } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodyMedium, color = muted)
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { if (it.length <= 2000) values[index] = it },
                        label = { Text(field.label.ifBlank { DeviceLabels.text("字段 ${index + 1}", "Field ${index + 1}") }) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitted,
                        singleLine = true,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text),
                    )
                }
                if (fields.isNotEmpty()) Row(verticalAlignment = Alignment.Top) {
                    AppIcon(AppIcons.Lock, null, Modifier.padding(top = 2.dp).size(14.dp), tint = muted)
                    Text(UiStrings.virtualScreenInputPrivacy, Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                val enabled = canTakeOver && !submitted
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled, role = Role.Button) { finish(onTakeOver()) }
                        .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val ink = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    AppIcon(AppIcons.Hand, null, Modifier.size(20.dp), tint = ink)
                    Column(Modifier.weight(1f)) {
                        Text(UiStrings.virtualScreenEnterTakeover, style = MaterialTheme.typography.titleSmall, color = ink)
                        Text(UiStrings.virtualScreenEnterTakeoverDetail, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    AppIcon(AppIcons.ChevronRight, null, Modifier.size(16.dp), tint = muted)
                }
            }
        },
        confirmButton = {
            if (fields.isNotEmpty()) TextButton(onClick = { if (!submitted) finish(onFill(values.toList())) },
                enabled = !submitted && values.any(String::isNotEmpty)) {
                Text(UiStrings.virtualScreenManualFill)
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!submitted) { submitted = true; onCancel() } }, enabled = !submitted) {
                Text(DeviceLabels.text("取消", "Cancel"))
            }
        },
    )
}
