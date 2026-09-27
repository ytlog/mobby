package com.github.ytlog.mobby.android.interaction.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.github.ytlog.mobby.android.device.DeviceStorage
import com.github.ytlog.mobby.android.interaction.domain.Conversation
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.DataResult
import com.github.ytlog.mobby.android.interaction.domain.OperationResult
import com.github.ytlog.mobby.android.interaction.domain.Plugin
import com.github.ytlog.mobby.android.interaction.domain.PluginAccess
import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings
import kotlinx.coroutines.delay

@Composable
internal fun rememberDirectPluginSelector(vm: ConversationViewModel, conversation: Conversation?): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<ConversationId, String>?>(null) }
    var rationale by remember { mutableStateOf<Plugin?>(null) }

    fun finishAccess(attempt: Boolean) {
        val request = pending
        pending = null
        rationale = null
        if (!attempt || request == null) return
        vm.enqueue {
            val (id, ref) = request
            var result = vm.actions.plugins()
            var retries = 0
            while (retries < 4 && result is DataResult.Loaded &&
                result.value.firstOrNull { it.ref == ref }?.let { !it.available && it.access == PluginAccess.ACCESSIBILITY } == true) {
                delay(250)
                result = vm.actions.plugins()
                retries++
            }
            when (result) {
                is DataResult.Failed -> vm.report(OperationResult.Failed(result.message))
                is DataResult.Loaded -> {
                    val plugin = result.value.firstOrNull { it.ref == ref }
                    when {
                        plugin == null -> vm.report(OperationResult.Failed(AppStrings.unknownPlugin))
                        plugin.available -> vm.report(vm.actions.setPlugin(id, plugin, true))
                        else -> vm.report(OperationResult.Failed(plugin.unavailableReason ?: AppStrings.grantPermissionBeforeUse))
                    }
                }
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finishAccess(true) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) finishAccess(false)
        else {
            val stored = runCatching { DeviceStorage.persist(context, uri) }
            if (stored.isFailure) vm.report(OperationResult.Failed(AppStrings.grantPermissionBeforeUse))
            finishAccess(stored.isSuccess)
        }
    }
    val accessibility = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishAccess(true) }

    rationale?.let { plugin ->
        AlertDialog(
            onDismissRequest = { finishAccess(false) },
            containerColor = raisedColor(),
            title = { Text(AppStrings.allowThisPluginToUseDeviceCapabilities) },
            text = { Text(plugin.description) },
            confirmButton = { TextButton(onClick = { rationale = null; permissions.launch(plugin.permissions.toTypedArray()) }) { Text(AppStrings.`continue`) } },
            dismissButton = { TextButton(onClick = { finishAccess(false) }) { Text(AppStrings.cancel) } },
        )
    }

    return { ref ->
        val id = conversation?.id
        if (id != null && pending == null) vm.enqueue {
            when (val result = vm.actions.plugins()) {
                is DataResult.Failed -> vm.report(OperationResult.Failed(result.message))
                is DataResult.Loaded -> {
                    val plugin = result.value.firstOrNull { it.ref == ref }
                    when {
                        plugin == null -> vm.report(OperationResult.Failed(AppStrings.unknownPlugin))
                        plugin.available -> vm.report(vm.actions.setPlugin(id, plugin, true))
                        plugin.access == PluginAccess.ACCESSIBILITY -> {
                            pending = id to ref
                            accessibility.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        plugin.access == PluginAccess.DOCUMENT_TREE -> {
                            pending = id to ref
                            tree.launch(null)
                        }
                        plugin.access == PluginAccess.RUNTIME && plugin.permissions.isNotEmpty() -> {
                            pending = id to ref
                            rationale = plugin
                        }
                        else -> vm.report(OperationResult.Failed(plugin.unavailableReason ?: AppStrings.grantPermissionBeforeUse))
                    }
                }
            }
        }
    }
}
