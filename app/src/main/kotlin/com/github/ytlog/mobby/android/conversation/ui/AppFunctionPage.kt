package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.github.ytlog.mobby.android.conversation.domain.*
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

@Composable internal fun AppFunctionPage(vm: ConversationViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val directory by vm.appFunctions.collectAsStateWithLifecycle()
    val loading by vm.appFunctionsLoading.collectAsStateWithLifecycle()
    val error by vm.appFunctionsError.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<PublishedAppFunction?>(null) }
    val conversation = state.selected?.conversation
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.loadAppFunctions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val displayed = directory.functions.filter { item ->
        query.isBlank() || item.appName.contains(query, true) || item.packageName.contains(query, true) ||
            item.functionId.contains(query, true) || item.description.contains(query, true)
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(AppStrings.appFunctions, onBack, trailing = { TextButton(onClick = vm::loadAppFunctions) { Text(AppStrings.retry) } })
        Text(AppStrings.appFunctionsDiscoveryNote, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (directory.availability == AppFunctionAvailability.AVAILABLE) {
            OutlinedTextField(query, { query = it }, label = { Text(AppStrings.searchAppFunctions) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (!loading && error == null) when (directory.availability) {
            AppFunctionAvailability.UNSUPPORTED_DEVICE -> EmptyPlaceholder(AppStrings.appFunctionsUnsupportedDevice)
            AppFunctionAvailability.PERMISSION_DENIED -> EmptyPlaceholder(AppStrings.appFunctionsPermissionDenied)
            AppFunctionAvailability.SYSTEM_DENIED -> EmptyPlaceholder(AppStrings.appFunctionsSystemDenied)
            AppFunctionAvailability.QUERY_FAILED -> EmptyPlaceholder(AppStrings.appFunctionsQueryFailed)
            AppFunctionAvailability.AVAILABLE -> if (displayed.isEmpty()) EmptyPlaceholder(AppStrings.appFunctionsEmpty)
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    displayed.groupBy { it.appName to it.packageName }.forEach { (source, functions) ->
                        item(key = "header:${source.second}") {
                            Column(Modifier.padding(top = 8.dp, bottom = 2.dp)) {
                                Text(source.first, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(source.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(functions, key = { it.ref }) { function ->
                            val chosen = function.ref in conversation?.draft?.capabilities.orEmpty()
                            val name = function.functionId.substringAfterLast('#').substringAfterLast('.')
                            CatalogRow(title = name, subtitle = function.description.ifBlank { function.functionId },
                                icon = AppIcons.Plugin, iconForeground = MaterialTheme.colorScheme.primary,
                                iconBackground = MaterialTheme.colorScheme.primaryContainer,
                                action = if (chosen) AppStrings.remove else AppStrings.viewAppFunction,
                                actionEnabled = chosen || function.enabled,
                                onAction = {
                                    if (chosen && conversation != null) vm.enqueue { vm.report(vm.actions.setAppFunction(conversation.id, function, false)) }
                                    else detail = function
                                })
                            if (!function.enabled) Text(when (function.unavailableReason) {
                                "unsupported_type" -> AppStrings.appFunctionUnsupportedType
                                else -> AppStrings.appFunctionDisabled
                            }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
        }
    }
    detail?.let { function ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(function.functionId.substringAfterLast('#').substringAfterLast('.')) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(function.appName + " · " + function.packageName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (function.description.isNotBlank()) Text(function.description)
                    Text(AppStrings.appFunctionParameters, fontWeight = FontWeight.SemiBold)
                    function.parameters.forEach { parameter ->
                        Text("${parameter.name} · ${parameter.type} · ${if (parameter.required) AppStrings.appFunctionRequired else AppStrings.appFunctionOptional}",
                            style = MaterialTheme.typography.bodyMedium)
                        if (parameter.description.isNotBlank()) Text(parameter.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(AppStrings.appFunctionTurnNote, style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton(onClick = {
                    detail = null
                    if (conversation != null) vm.enqueue { vm.report(vm.actions.setAppFunction(conversation.id, function, true)) }
                }, enabled = conversation != null && function.enabled) { Text(AppStrings.addAppFunctionToTurn) }
            }, dismissButton = { TextButton(onClick = { detail = null }) { Text(AppStrings.cancel) } })
    }
}
