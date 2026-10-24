package com.github.ytlog.mobby.android.programcenter

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.ui.*
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.*
import com.github.ytlog.mobby.android.programs.capability.Availability
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.ConfigType
import com.github.ytlog.mobby.android.programs.program.InstallPreview
import com.github.ytlog.mobby.android.programs.program.PackageRejected
import com.github.ytlog.mobby.android.programs.program.UserState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File

private const val LIST = "list"
private const val INSTALL = "install"
private const val PERMISSIONS = "permissions"
private const val DETAIL = "detail:"

/** Program center: install, enable, grant, configure, stop and inspect programs; plus app-level permission management. */
@Composable fun ProgramCenterPage(platform: ProgramPlatform, back: () -> Unit, modifyWithAi: (ProgramView) -> Unit = {}) {
    var route by rememberSaveable { mutableStateOf(LIST) }
    val programs by platform.programs.collectAsState(initial = emptyList())
    BackHandler(route != LIST) { route = LIST }
    when {
        route == INSTALL -> InstallPage(platform) { installed -> route = installed?.let { DETAIL + it } ?: LIST }
        route == PERMISSIONS -> PermissionManagementPage(platform, programs) { route = LIST }
        route.startsWith(DETAIL) -> programs.firstOrNull { it.id == route.removePrefix(DETAIL) }
            ?.let { ProgramDetailPage(platform, it, modifyWithAi) { route = LIST } } ?: LaunchedEffect(route) { route = LIST }
        else -> ProgramListPage(platform, programs, back, { route = DETAIL + it }, { route = INSTALL }, { route = PERMISSIONS })
    }
}

@Composable private fun Page(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable private fun Line(title: String, detail: String? = null, error: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ProgramListPage(platform: ProgramPlatform, programs: List<ProgramView>, back: () -> Unit,
    open: (String) -> Unit, install: () -> Unit, permissions: () -> Unit) {
    val scope = rememberCoroutineScope()
    Page(AppStrings.programCenter, back) {
        SettingsGroup {
            SettingsItem(AppStrings.installProgram, install)
            GroupDivider()
            SettingsItem(AppStrings.permissionManagement, permissions)
        }
        if (programs.isEmpty()) SettingsCaption(AppStrings.noPrograms)
        else SettingsGroup {
            programs.forEachIndexed { index, program ->
                if (index > 0) GroupDivider()
                SettingsItem(program.name, { open(program.id) }, "${AppStrings.versionLabel(program.version)} · ${availabilityLabel(program.availability)}")
            }
            GroupDivider()
            SettingsAction(AppStrings.stopAllPrograms) { scope.launch { platform.stopAll() } }
        }
        SettingsCaption(AppStrings.programBoundary)
    }
}

@Composable private fun InstallPage(platform: ProgramPlatform, done: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var candidates by remember { mutableStateOf(emptyList<ProgramCandidate>()) }
    var preview by remember { mutableStateOf<InstallPreview?>(null) }
    var chosen by remember { mutableStateOf<File?>(null) }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { platform.candidates() } }
    Page(AppStrings.installProgram, { done(null) }) {
        SettingsCaption(AppStrings.installHint("~/workspace/programs"))
        if (candidates.isEmpty()) SettingsCaption(AppStrings.noCandidates)
        else SettingsGroup {
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) GroupDivider()
                SettingsItem(candidate.name, {
                    scope.launch {
                        chosen = candidate.directory
                        try { preview = platform.preview(candidate.directory); problems = emptyList() }
                        catch (error: PackageRejected) { preview = null; problems = error.problems.map { "${it.path}: ${it.message}" } }
                    }
                })
            }
        }
        if (problems.isNotEmpty()) {
            SettingsCaption(AppStrings.packageRejected, error = true)
            problems.forEach { SettingsCaption("• $it", error = true) }
        }
        preview?.let { p ->
            val manifest = p.pkg.manifest
            SettingsGroup(manifest.name) {
                Line(AppStrings.versionLabel(manifest.version), listOfNotNull(p.installedVersion?.let(AppStrings::updatingFrom), AppStrings.fileCount(p.pkg.files.size)).joinToString(" · "))
            }
            SettingsGroup(AppStrings.requestedPermissions) {
                p.pkg.permissions.forEachIndexed { index, request ->
                    if (index > 0) GroupDivider()
                    Line("${request.capability} · ${if (request.required) AppStrings.required else AppStrings.optional}",
                        listOfNotNull(scopeSummary(context, request.scope).ifEmpty { null },
                            AppStrings.needsYourGrant.takeIf { request.capability in p.needsNewGrant }).joinToString(" · "))
                }
            }
            if (manifest.config.isNotEmpty()) SettingsGroup(AppStrings.configFields) {
                manifest.config.forEach { Line(it.label, "${it.key} · ${it.type}${if (it.required) " · ${AppStrings.required}" else ""}") }
            }
            if (manifest.subscriptions.isNotEmpty()) SettingsGroup(AppStrings.subscribedEvents) {
                manifest.subscriptions.forEach { Line(it.event, "${it.filter} → ${it.handler}") }
            }
            SettingsCaption(AppStrings.programBoundary)
            SettingsGroup {
                SettingsAction(if (p.installedVersion == null) AppStrings.install else AppStrings.update) {
                    scope.launch {
                        try { done(platform.install(chosen!!).programId) }
                        catch (error: PackageRejected) { preview = null; problems = error.problems.map { "${it.path}: ${it.message}" } }
                    }
                }
            }
        }
    }
}

@Composable private fun ProgramDetailPage(platform: ProgramPlatform, program: ProgramView, modifyWithAi: (ProgramView) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val runs by remember(platform) { platform.runs() }.collectAsState(initial = emptyList())
    val logs by remember(platform, program.id) { platform.logs(program.id, 20) }.collectAsState(initial = emptyList())
    Page(program.name, back) {
        SettingsGroup {
            Line(AppStrings.versionLabel(program.version), availabilityLabel(program.availability))
            GroupDivider()
            SettingsToggle(AppStrings.programEnabled, program.userState == UserState.ENABLED) { scope.launch { platform.setEnabled(program.id, it) } }
            if (program.hasUi) { GroupDivider(); SettingsAction(AppStrings.openProgramUi) { context.startActivity(ProgramUiActivity.intent(context, program.id)) } }
            if (program.hasBackground) { GroupDivider(); SettingsAction(AppStrings.stopRun) { scope.launch { platform.stop(program.id) } } }
            GroupDivider()
            SettingsAction(AppStrings.modifyWithAi) { modifyWithAi(program) }
            GroupDivider()
            SettingsAction(AppStrings.deleteProgram) { confirmDelete = true }
        }
        SettingsCaption(AppStrings.programBoundary)
        SettingsGroup(AppStrings.permissionsTitle) {
            program.permissions.forEachIndexed { index, permission ->
                if (index > 0) GroupDivider()
                PermissionEditor(permission) { enabled, scopeValue, expiresAt ->
                    scope.launch { platform.setGrant(program.id, permission.capability, enabled, scopeValue, expiresAt) }
                }
            }
        }
        if (program.requests.isNotEmpty()) SettingsGroup(AppStrings.pendingRequests) {
            program.requests.forEach { request ->
                Line(request.capability, scopeSummary(context, request.scope))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { scope.launch { platform.resolveRequest(request, program.id, true) } }) { Text(AppStrings.approve) }
                    TextButton(onClick = { scope.launch { platform.resolveRequest(request, program.id, false) } }) { Text(AppStrings.decline) }
                }
            }
        }
        if (program.config.isNotEmpty()) SettingsGroup(AppStrings.configFields) {
            program.config.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                ConfigEditor(item, { value -> scope.launch { platform.setConfig(program.id, item.field.key, value) } },
                    { secret -> scope.launch { platform.setSecretField(program.id, item.field.key, secret) } })
            }
        }
        if (program.subscriptions.isNotEmpty()) SettingsGroup(AppStrings.subscribedEvents) {
            program.subscriptions.forEachIndexed { index, subscription ->
                if (index > 0) GroupDivider()
                Line("${subscription.event} → ${subscription.handler}", listOf(
                    subscription.filter.toString(),
                    if (subscription.active) AppStrings.subscriptionActive else AppStrings.subscriptionInactive,
                    subscription.lastTriggeredAt?.let { AppStrings.triggered(subscription.triggerCount, formatTime(it)) } ?: AppStrings.neverTriggered,
                ).joinToString(" · "))
                SettingsToggle(AppStrings.receiveEvents, !subscription.paused) { receive -> scope.launch { platform.setSubscriptionPaused(subscription.id, !receive) } }
            }
        }
        SettingsGroup(AppStrings.runRecords) {
            val own = runs.filter { it.programId == program.id }.take(30)
            if (own.isEmpty()) Line(AppStrings.noRuns)
            own.forEachIndexed { index, run ->
                if (index > 0) GroupDivider()
                Line("${formatTime(run.queuedAt)} · ${runOutcomeLabel(run.outcome)}", listOfNotNull(runTriggerLabel(run.trigger), run.reason?.let(::runReasonLabel),
                    run.exitCode?.let(AppStrings::exitCode), run.signal?.let(AppStrings::exitSignal),
                    run.droppedEvents.takeIf { it > 0 }?.let(AppStrings::droppedEvents)).joinToString(" · "))
            }
        }
        if (logs.isNotEmpty()) SettingsGroup(AppStrings.programLog) {
            logs.forEach { Line("${formatTime(it.at)} ${it.message}${it.dataJson?.let { data -> " $data" } ?: ""}") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = raisedColor(), shape = RoundedCornerShape(24.dp),
        title = { Text(AppStrings.deleteProgram) }, text = { Text(AppStrings.deleteConfirm(program.name)) },
        confirmButton = {
            Column {
                TextButton(onClick = { confirmDelete = false; scope.launch { platform.delete(program.id, withData = false); back() } }) { Text(AppStrings.deleteKeepData) }
                TextButton(onClick = { confirmDelete = false; scope.launch { platform.delete(program.id, withData = true); back() } }) { Text(AppStrings.deleteWithData) }
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(AppStrings.cancel) } })
}

@Composable private fun PermissionEditor(permission: PermissionView, save: (Boolean, Scope?, Long?) -> Unit) {
    val context = LocalContext.current
    Line("${permission.capability} · ${if (permission.required) AppStrings.required else AppStrings.optional}",
        listOfNotNull(permissionLabel(permission.state), permission.expiresAt?.let { AppStrings.expiresAt(formatTime(it)) }).joinToString(" · "),
        error = permission.state != com.github.ytlog.mobby.android.programs.permission.PermissionState.GRANTED)
    SettingsToggle(permission.capability, permission.enabled) { save(it, null, permission.expiresAt) }
    val requested = permission.requested
    val granted = permission.granted
    fun choose(picked: Set<String>?, item: String, rebuild: (Set<String>) -> Scope) {
        val now = picked.orEmpty()
        save(permission.enabled, rebuild(if (item in now) now - item else now + item), permission.expiresAt)
    }
    requested.packages?.sorted()?.forEach { item ->
        CheckRow(appLabel(context, item), item in granted.packages.orEmpty()) { choose(granted.packages, item) { granted.copy(packages = it) } }
    }
    requested.domains?.sorted()?.forEach { item ->
        CheckRow(item, item in granted.domains.orEmpty()) { choose(granted.domains, item) { granted.copy(domains = it) } }
    }
    if (requested.insecureHttp) CheckRow(AppStrings.allowPlainHttp, granted.insecureHttp) { save(permission.enabled, granted.copy(insecureHttp = !granted.insecureHttp), permission.expiresAt) }
    requested.methods?.let { Text(AppStrings.methods(it.sorted().joinToString()), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val hour = 3_600_000L
        listOf(AppStrings.noExpiry to null, AppStrings.expireHour to hour, AppStrings.expireDay to 24 * hour, AppStrings.expireWeek to 7 * 24 * hour).forEach { (label, span) ->
            CatalogChip(label, span == null && permission.expiresAt == null) { save(permission.enabled, null, span?.let { System.currentTimeMillis() + it }) }
        }
    }
}

@Composable private fun CheckRow(label: String, checked: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ConfigEditor(item: ConfigView, save: (JsonElement?) -> Unit, saveSecret: (String) -> Unit) {
    val field = item.field
    val type = ConfigType.of(field.type)
    Line("${field.label}${if (field.required) " · ${AppStrings.required}" else ""}",
        if (type == ConfigType.SECRET) (if (item.secretSet) AppStrings.secretSet else AppStrings.secretNotSet) else item.value?.toString())
    when (type) {
        ConfigType.BOOLEAN -> SettingsToggle(field.key, (item.value as? JsonPrimitive)?.booleanOrNull == true) { save(JsonPrimitive(it)) }
        ConfigType.ENUM -> Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            field.options.orEmpty().forEach { option -> CatalogChip(option, (item.value as? JsonPrimitive)?.contentOrNull == option) { save(JsonPrimitive(option)) } }
        }
        else -> {
            var text by remember(item.value) { mutableStateOf(if (type == ConfigType.SECRET) "" else (item.value as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { if (type == ConfigType.SECRET) Text(AppStrings.replaceSecret) },
                    visualTransformation = if (type == ConfigType.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                TextButton(onClick = {
                    when (type) {
                        ConfigType.SECRET -> if (text.isNotEmpty()) { saveSecret(text); text = "" }
                        ConfigType.NUMBER -> text.toDoubleOrNull()?.let { save(JsonPrimitive(if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong() else it)) }
                        else -> save(if (text.isEmpty()) null else JsonPrimitive(text))
                    }
                }) { Text(AppStrings.save) }
            }
        }
    }
}

@Composable private fun PermissionManagementPage(platform: ProgramPlatform, programs: List<ProgramView>, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val switches by platform.switches.collectAsState(initial = emptyMap())
    val capabilities = remember(programs) { platform.capabilities() }
    Page(AppStrings.permissionManagement, back) {
        SettingsGroup(AppStrings.systemCapabilities) {
            capabilities.forEachIndexed { index, capability ->
                if (index > 0) GroupDivider()
                val availability = capability.availability
                Line(capability.id, listOfNotNull(
                    if (availability is Availability.Unavailable) availability.reason.name.lowercase() else AppStrings.available,
                    AppStrings.implicitCapability.takeIf { capability.implicit },
                ).joinToString(" · "), error = availability is Availability.Unavailable)
            }
            GroupDivider()
            SettingsAction(AppStrings.openSystemSettings) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
            }
        }
        SettingsGroup(AppStrings.globalSwitches) {
            capabilities.filterNot { it.implicit }.forEachIndexed { index, capability ->
                if (index > 0) GroupDivider()
                SettingsToggle(capability.id, switches[capability.id] ?: true) { scope.launch { platform.setSwitch(capability.id, it) } }
            }
        }
        SettingsCaption(AppStrings.globalSwitchNote)
        SettingsGroup(AppStrings.byCapability) {
            capabilities.filterNot { it.implicit }.forEachIndexed { index, capability ->
                if (index > 0) GroupDivider()
                val holders = programs.mapNotNull { p -> p.permissions.firstOrNull { it.capability == capability.id && it.enabled }?.let { p to it } }
                Line(capability.id, if (holders.isEmpty()) AppStrings.heldByNone
                    else AppStrings.heldBy(holders.joinToString { (p, perm) -> "${p.name}（${permissionLabel(perm.state)}）" }))
            }
        }
        SettingsGroup(AppStrings.byProgram) {
            if (programs.isEmpty()) Line(AppStrings.noPrograms)
            programs.forEachIndexed { index, program ->
                if (index > 0) GroupDivider()
                Line("${program.name} · ${availabilityLabel(program.availability)}",
                    program.permissions.joinToString("\n") { "${it.capability}: ${permissionLabel(it.state)}" })
            }
        }
        SettingsGroup(AppStrings.bySource) {
            programs.flatMap { p -> p.subscriptions.map { p to it } }.groupBy { it.second.event }.forEach { (event, items) ->
                Line(event, items.joinToString("\n") { (p, s) ->
                    "${p.name} · ${s.id.substringAfter('/')} · ${if (s.active) AppStrings.subscriptionActive else AppStrings.subscriptionInactive}"
                })
            }
        }
    }
}
