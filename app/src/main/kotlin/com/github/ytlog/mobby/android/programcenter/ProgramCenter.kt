package com.github.ytlog.mobby.android.programcenter

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.ytlog.mobby.android.conversation.ui.*
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.programs.*
import com.github.ytlog.mobby.android.programs.base.ai.AiBinding
import com.github.ytlog.mobby.android.programs.base.ai.ModelGatewayPort
import com.github.ytlog.mobby.android.programs.capability.Availability
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.ConfigType
import com.github.ytlog.mobby.android.programs.program.PackageChanged
import com.github.ytlog.mobby.android.programs.program.UserState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private const val LIST = "list"
private const val INSTALL = "install:"
private const val PERMISSIONS = "permissions"
private const val DETAIL = "detail:"

/**
 * Programs, the single entry for them: workspace programs waiting to be installed, installed programs with their state,
 * one-step install, and each program's details. Program development itself happens in a conversation.
 */
@Composable fun ProgramCenterPage(platform: ProgramPlatform, back: () -> Unit, writeNew: (idea: String?) -> Unit, program: String? = null,
    modifyWithAi: (ProgramView) -> Unit = {}) {
    var route by rememberSaveable(program) { mutableStateOf(program?.let { DETAIL + it } ?: LIST) }
    val loaded by platform.programs.collectAsState(initial = null)
    val programs = loaded.orEmpty()
    // The AI writes into the workspace while this page is in the background, so look again whenever it comes back.
    var pending by remember { mutableStateOf<List<PendingInstall>?>(null) }
    var checks by remember { mutableIntStateOf(0) }
    LaunchedEffect(checks) { pending = platform.pendingInstalls() }
    OnResume { checks++ }
    BackHandler(route != LIST) { route = LIST }
    when {
        route.startsWith(INSTALL) -> {
            val name = route.removePrefix(INSTALL)
            val item = pending?.firstOrNull { it.candidate.name == name }
            if (item != null) InstallPage(platform, item, programs, reload = { checks++ }) { route = LIST; checks++ }
            else if (pending != null) LaunchedEffect(route) { route = LIST }
        }
        route == PERMISSIONS -> PermissionManagementPage(platform, programs) { route = LIST }
        route.startsWith(DETAIL) -> {
            val id = route.removePrefix(DETAIL)
            when (detailTarget(id, loaded?.map { it.id }?.toSet())) {
                DetailTarget.SHOW -> ProgramDetailPage(platform, programs.first { it.id == id }, modifyWithAi) { route = LIST }
                DetailTarget.WAIT -> Unit
                DetailTarget.GONE -> LaunchedEffect(route) { route = LIST }
            }
        }
        else -> ProgramListPage(platform, programs, pending.orEmpty(), back, writeNew, { route = DETAIL + it }, { route = INSTALL + it }, { route = PERMISSIONS })
    }
}

internal enum class DetailTarget { SHOW, WAIT, GONE }

/** A details route shows its program, waits while the list loads, and otherwise the program is gone. */
internal fun detailTarget(id: String, loaded: Set<String>?) = when {
    loaded == null -> DetailTarget.WAIT
    id in loaded -> DetailTarget.SHOW
    else -> DetailTarget.GONE
}

@Composable private fun OnResume(action: () -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(action)
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) current() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable private fun Page(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(title, back, trailing)
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

/** Today's runs show the time only; older ones add the date. */
private fun shortTime(millis: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val pattern = if (at.toLocalDate() == java.time.LocalDate.now(zone)) "HH:mm" else "MM-dd HH:mm"
    return at.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}

@Composable private fun ProgramListPage(platform: ProgramPlatform, programs: List<ProgramView>, pending: List<PendingInstall>, back: () -> Unit,
    writeNew: (idea: String?) -> Unit, open: (String) -> Unit, install: (String) -> Unit, permissions: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var backgroundAllowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    OnResume { backgroundAllowed = power.isIgnoringBatteryOptimizations(context.packageName) }
    val runs by remember(platform) { platform.runs() }.collectAsState(initial = emptyList())
    val lastRuns = remember(runs) { runs.filter { !it.dryRun }.groupBy { it.programId }.mapValues { it.value.maxBy { run -> run.queuedAt } } }
    var menu by remember { mutableStateOf(false) }
    Page(AppStrings.myPrograms, back, trailing = {
        Box {
            ActionIcon(AppStrings.moreActions, { menu = true }, AppIcons.More)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(AppStrings.permissionOverview) }, onClick = { menu = false; permissions() })
                DropdownMenuItem(text = { Text(AppStrings.pauseAllPrograms) }, onClick = { menu = false; scope.launch { platform.stopAll() } })
            }
        }
    }) {
        BackgroundStatus(backgroundAllowed) {
            // The system asks the user; mobby never changes the setting itself.
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")))
        }
        SettingsGroup { SettingsItem(AppStrings.writeNewProgram, { writeNew(null) }, AppStrings.writeNewProgramSummary, icon = AppIcons.Terminal) }
        ProgramIdeas(writeNew)
        if (pending.isNotEmpty()) SettingsGroup(AppStrings.pendingPrograms(pending.size)) {
            pending.forEachIndexed { index, item ->
                if (index > 0) GroupDivider()
                val name = (item as? PendingInstall.Ready)?.preview?.pkg?.manifest?.name ?: item.candidate.name
                val action = when { item is PendingInstall.Rejected -> AppStrings.review; (item as PendingInstall.Ready).preview.installedVersion == null -> AppStrings.install; else -> AppStrings.update }
                ProgramRow(name, pendingStatus(item), { install(item.candidate.name) }) {
                    TextButton(onClick = { install(item.candidate.name) }) { Text(action) }
                }
            }
        }
        if (programs.isNotEmpty()) SettingsGroup(AppStrings.installedPrograms(programs.size)) {
            programs.forEachIndexed { index, program ->
                if (index > 0) GroupDivider()
                ProgramRow(program.name, programStatus(program, lastRuns[program.id], ::shortTime), { open(program.id) }) {
                    Switch(program.userState == UserState.ENABLED, { enabled -> scope.launch { platform.setEnabled(program.id, enabled) } })
                }
            }
        }
        if (pending.isEmpty() && programs.isEmpty()) SettingsCaption(AppStrings.emptyProgramsBody)
    }
}

/** A program as a row: its initial, name and state in words, and one control at the end. */
@Composable private fun ProgramRow(name: String, status: StatusLine, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
            Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (status.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Shown only while scheduled programs may be held back; mobby is exempt otherwise and there is nothing to say. */
@Composable internal fun BackgroundStatus(allowed: Boolean, request: () -> Unit) {
    if (allowed) return
    Surface(onClick = request, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppStrings.backgroundRestrictedShort, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(AppStrings.allowShort, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Installing in one step: what the program will do in plain words, the settings it cannot work without, and one
 * button that installs, grants and (for a new program) enables it. Scopes and events stay one tap away.
 */
@Composable private fun InstallPage(platform: ProgramPlatform, item: PendingInstall, programs: List<ProgramView>, reload: () -> Unit, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    @Composable fun Dismiss() {
        SettingsGroup { SettingsAction(AppStrings.dismissVersion) { scope.launch { platform.dismiss(item.candidate); done() } } }
        SettingsCaption(AppStrings.dismissHint)
    }
    when (item) {
        is PendingInstall.Rejected -> Page(item.candidate.name, done) {
            SettingsCaption(AppStrings.rejectedHint)
            SettingsGroup { item.problems.forEachIndexed { index, problem -> if (index > 0) GroupDivider(); Line(problem.message, problem.path, error = true) } }
            Dismiss()
        }
        is PendingInstall.Ready -> {
            val preview = item.preview
            val manifest = preview.pkg.manifest
            val installed = programs.firstOrNull { it.id == manifest.id }
            val isNew = preview.installedVersion == null
            val choices = remember(preview) { GrantChoices(preview) }
            var ticked by remember(preview) { mutableStateOf(choices.initiallyTicked) }
            val fields = remember(preview, installed) { fieldsToFill(manifest.config, installed) }
            val inputs = remember(preview) { mutableStateMapOf<String, String>() }
            var error by remember(preview) { mutableStateOf<String?>(null) }
            var details by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            val schedules = manifest.subscriptions.filter { it.event == "time.schedule" }.map { it.filter }
            val models = remember(preview) { modelOptions(platform.modelGateways) }
            var model by remember(preview) { mutableStateOf(initialModel(models, platform.aiBinding(manifest.id), platform.modelGateways?.preferred())) }
            val asksAi = ProgramPlatform.AI_CALL in choices.toApply(ticked)
            Page(if (isNew) AppStrings.installTitle(manifest.name) else AppStrings.updateTitle(manifest.name), done) {
                SettingsGroup(AppStrings.whatItDoes) {
                    preview.pkg.permissions.forEachIndexed { index, request ->
                        if (index > 0) GroupDivider()
                        val label = purposeLabel(request.capability, request.scope, schedules) +
                            (if (!isNew && request.capability in preview.newlyRequested) " · ${AppStrings.newlyAsked}" else "") +
                            (if (!request.required) " · ${AppStrings.optional}" else "")
                        if (request.capability in choices.tickable) CheckRow(label, request.capability in ticked) {
                            ticked = if (request.capability in ticked) ticked - request.capability else ticked + request.capability
                        } else Line(label)
                    }
                }
                if (asksAi) ModelChoice(models, model) { model = it; error = null }
                if (fields.isNotEmpty()) SettingsGroup(AppStrings.needsYourInput) {
                    fields.forEachIndexed { index, field ->
                        if (index > 0) GroupDivider()
                        FieldInput(field, inputs[field.key].orEmpty()) { inputs[field.key] = it; error = null }
                    }
                }
                error?.let { SettingsCaption(it, error = true) }
                SettingsGroup {
                    SettingsAction(if (isNew) AppStrings.installAndEnable else AppStrings.update, enabled = !busy) {
                        val values = mutableMapOf<String, JsonElement>()
                        val secrets = mutableMapOf<String, String>()
                        for (field in fields) {
                            val text = inputs[field.key].orEmpty()
                            if (text.isBlank()) { error = AppStrings.fillFirst(field.label); return@SettingsAction }
                            if (field.type == ConfigType.SECRET.wire) { secrets[field.key] = text; continue }
                            val value = fieldValue(field, text)?.takeIf { com.github.ytlog.mobby.android.programs.base.config.ConfigStore.validate(field, it) == null }
                                ?: run { error = AppStrings.invalidValue(field.label); return@SettingsAction }
                            values[field.key] = value
                        }
                        val ai = if (asksAi) {
                            val chosen = model ?: run { error = AppStrings.noModelGateway; return@SettingsAction }
                            platform.aiBinding(manifest.id)?.copy(gatewayId = chosen.gatewayId, model = chosen.model) ?: AiBinding(chosen.gatewayId, chosen.model)
                        } else null
                        busy = true
                        scope.launch {
                            try {
                                platform.install(item.candidate.directory, InstallChoice(preview.pkg.checksum, choices.toApply(ticked), values, secrets, enable = isNew, ai = ai))
                                Toast.makeText(context, if (isNew) AppStrings.installed else AppStrings.updated, Toast.LENGTH_SHORT).show()
                                done()
                            } catch (_: PackageChanged) { error = AppStrings.programChangedReview; reload() }
                            catch (failure: IllegalArgumentException) { error = AppStrings.installFailed(failure.message) }
                            finally { busy = false }
                        }
                    }
                }
                SettingsGroup {
                    SettingsAction(if (details) AppStrings.hidePermissionDetails else AppStrings.permissionDetails) { details = !details }
                }
                if (details) {
                    SettingsGroup(AppStrings.requestedPermissions) {
                        preview.pkg.permissions.forEachIndexed { index, request ->
                            if (index > 0) GroupDivider()
                            Line("${capabilityName(request.capability)} · ${if (request.required) AppStrings.required else AppStrings.optional}",
                                listOfNotNull(scopeSummary(context, request.scope).ifEmpty { null },
                                    AppStrings.needsYourGrant.takeIf { request.capability in preview.needsNewGrant }).joinToString(" · "))
                        }
                    }
                    if (manifest.subscriptions.isNotEmpty()) SettingsGroup(AppStrings.subscribedEvents) {
                        manifest.subscriptions.forEach { Line(capabilityName(it.event), "${it.filter} → ${it.handler}") }
                    }
                    SettingsCaption(listOfNotNull(AppStrings.versionLabel(manifest.version), preview.installedVersion?.let(AppStrings::updatingFrom),
                        AppStrings.fileCount(preview.pkg.files.size)).joinToString(" · "))
                }
                SettingsCaption(AppStrings.programBoundary)
                Dismiss()
            }
        }
    }
}

/** A setting typed in before installing; nothing is saved until the install button. */
@Composable private fun FieldInput(field: com.github.ytlog.mobby.android.programs.program.ConfigField, text: String, change: (String) -> Unit) {
    val type = ConfigType.of(field.type)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(field.label, style = MaterialTheme.typography.bodyLarge)
        when (type) {
            ConfigType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("true" to AppStrings.yes, "false" to AppStrings.no).forEach { (value, label) -> CatalogChip(label, text == value) { change(value) } }
            }
            ConfigType.ENUM -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                field.options.orEmpty().forEach { option -> CatalogChip(option, text == option) { change(option) } }
            }
            else -> OutlinedTextField(text, change, Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = configKeyboard(type),
                visualTransformation = if (type == ConfigType.SECRET) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
    }
}

@Composable private fun ProgramDetailPage(platform: ProgramPlatform, program: ProgramView, modifyWithAi: (ProgramView) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val launcher = remember(context) { ProgramLauncherShortcuts(context) }
    val runs by remember(platform) { platform.runs() }.collectAsState(initial = emptyList())
    val logs by remember(platform, program.id) { platform.logs(program.id, 20) }.collectAsState(initial = emptyList())
    Page(program.name, back) {
        SettingsGroup {
            Line(AppStrings.versionLabel(program.version), availabilityLabel(program.availability))
            GroupDivider()
            SettingsToggle(AppStrings.programEnabled, program.userState == UserState.ENABLED) { scope.launch { platform.setEnabled(program.id, it) } }
            if (program.hasUi) { GroupDivider(); SettingsAction(AppStrings.openProgramUi) { context.startActivity(ProgramUiActivity.intent(context, program.id)) } }
            if (launcher.pinSupported()) { GroupDivider(); SettingsAction(AppStrings.addToHomeScreen) {
                // Some launchers (MIUI) drop the request silently unless the system allows mobby to create shortcuts; say where that is.
                if (launcher.requestPin(program)) Toast.makeText(context, AppStrings.addToHomeScreenRequested, Toast.LENGTH_LONG).show()
                else Toast.makeText(context, AppStrings.addToHomeScreenFailed, Toast.LENGTH_SHORT).show()
            } }
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
        if (program.permissions.any { it.capability == ProgramPlatform.AI_CALL }) AiModelSection(platform, program.id)
        if (program.requests.isNotEmpty()) SettingsGroup(AppStrings.pendingRequests) {
            program.requests.forEach { request ->
                Line(capabilityName(request.capability), scopeSummary(context, request.scope))
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
                Line("${capabilityName(subscription.event)} → ${subscription.handler}", listOf(
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
    Line("${capabilityName(permission.capability)} · ${if (permission.required) AppStrings.required else AppStrings.optional}",
        listOfNotNull(permissionLabel(permission.state), permission.expiresAt?.let { AppStrings.expiresAt(formatTime(it)) }).joinToString(" · "),
        error = permission.state != com.github.ytlog.mobby.android.programs.permission.PermissionState.GRANTED)
    SettingsToggle(capabilityName(permission.capability), permission.enabled) { save(it, null, permission.expiresAt) }
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

/** Secrets ask for password input so composing or learning input methods leave the typed value alone. */
internal fun configKeyboard(type: ConfigType?) = when (type) {
    ConfigType.SECRET -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    ConfigType.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Number)
    else -> KeyboardOptions(keyboardType = KeyboardType.Text)
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
                    placeholder = { if (type == ConfigType.SECRET) Text(AppStrings.replaceSecret) }, keyboardOptions = configKeyboard(type),
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
    Page(AppStrings.permissionOverview, back) {
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
                SettingsToggle(capabilityName(capability.id), switches[capability.id] ?: true) { scope.launch { platform.setSwitch(capability.id, it) } }
            }
        }
        SettingsCaption(AppStrings.globalSwitchNote)
        SettingsGroup(AppStrings.byCapability) {
            capabilities.filterNot { it.implicit }.forEachIndexed { index, capability ->
                if (index > 0) GroupDivider()
                val holders = programs.mapNotNull { p -> p.permissions.firstOrNull { it.capability == capability.id && it.enabled }?.let { p to it } }
                Line(capabilityName(capability.id), if (holders.isEmpty()) AppStrings.heldByNone
                    else AppStrings.heldBy(holders.joinToString { (p, perm) -> "${p.name}（${permissionLabel(perm.state)}）" }))
            }
        }
        SettingsGroup(AppStrings.byProgram) {
            if (programs.isEmpty()) Line(AppStrings.noPrograms)
            programs.forEachIndexed { index, program ->
                if (index > 0) GroupDivider()
                Line("${program.name} · ${availabilityLabel(program.availability)}",
                    program.permissions.joinToString("\n") { "${capabilityName(it.capability)}: ${permissionLabel(it.state)}" })
            }
        }
        SettingsGroup(AppStrings.bySource) {
            programs.flatMap { p -> p.subscriptions.map { p to it } }.groupBy { it.second.event }.forEach { (event, items) ->
                Line(capabilityName(event), items.joinToString("\n") { (p, s) ->
                    "${p.name} · ${s.id.substringAfter('/')} · ${if (s.active) AppStrings.subscriptionActive else AppStrings.subscriptionInactive}"
                })
            }
        }
    }
}

/** Starting points for a first program; picking one only fills the request, which the user can change before sending. */
@Composable internal fun ProgramIdeas(pick: (String) -> Unit) {
    val ideas = listOf(
        Triple(AppStrings.programIdeaExpenses, AppStrings.programIdeaExpensesDetail, AppStrings.programIdeaExpensesRequest),
        Triple(AppStrings.programIdeaReminder, AppStrings.programIdeaReminderDetail, AppStrings.programIdeaReminderRequest),
        Triple(AppStrings.programIdeaWatch, AppStrings.programIdeaWatchDetail, AppStrings.programIdeaWatchRequest),
    )
    SettingsGroup(AppStrings.programIdeas) {
        ideas.forEachIndexed { index, (title, detail, request) ->
            if (index > 0) GroupDivider()
            SettingsItem(title, { pick(request) }, detail)
        }
    }
}

/** One row per gateway and model the user configured. */
internal data class ModelOption(val gatewayId: String, val label: String, val model: String)

internal fun modelOptions(port: ModelGatewayPort?): List<ModelOption> =
    port?.gateways().orEmpty().flatMap { gateway -> gateway.models.map { ModelOption(gateway.id, gateway.label, it) } }

/** The program's current model first, then the conversations' default, then whatever is configured. */
internal fun initialModel(options: List<ModelOption>, current: AiBinding?, preferred: Pair<String, String>?): ModelOption? =
    options.firstOrNull { current != null && it.gatewayId == current.gatewayId && it.model == current.model }
        ?: options.firstOrNull { preferred != null && it.gatewayId == preferred.first && it.model == preferred.second }
        ?: options.firstOrNull()

@Composable private fun ModelChoice(options: List<ModelOption>, selected: ModelOption?, select: (ModelOption) -> Unit) {
    SettingsGroup(AppStrings.aiModel) {
        if (options.isEmpty()) Line(AppStrings.noModelGateway, error = true)
        options.forEachIndexed { index, option ->
            if (index > 0) GroupDivider()
            ChoiceRow("${option.label} · ${option.model}", option == selected, { select(option) })
        }
    }
    SettingsCaption(AppStrings.aiModelHint)
}

/** The model a program's `ai.call` uses, today's usage, and the user's way to change either. */
@Composable private fun AiModelSection(platform: ProgramPlatform, programId: String) {
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val binding = remember(revision) { platform.aiBinding(programId) }
    val usage = remember(revision) { platform.aiUsage(programId) }
    val options = remember(revision) { modelOptions(platform.modelGateways) }
    var editing by remember { mutableStateOf(false) }
    OnResume { revision++ }
    SettingsGroup(AppStrings.aiModel) {
        val label = binding?.let { bound -> options.firstOrNull { it.gatewayId == bound.gatewayId }?.label?.let { "$it · ${bound.model}" } ?: bound.model }
        Line(label ?: AppStrings.aiModelNotChosen, binding?.let { AppStrings.aiUsageToday(usage.calls, it.callsPerDay, usage.tokens, it.tokensPerDay) }, error = binding == null)
        GroupDivider()
        SettingsAction(if (editing) AppStrings.cancel else AppStrings.changeAiModel) { editing = !editing }
    }
    if (!editing) return
    var model by remember(binding) { mutableStateOf(initialModel(options, binding, platform.modelGateways?.preferred())) }
    var calls by remember(binding) { mutableStateOf((binding?.callsPerDay ?: AiBinding.DEFAULT_CALLS_PER_DAY).toString()) }
    var tokens by remember(binding) { mutableStateOf((binding?.tokensPerDay ?: AiBinding.DEFAULT_TOKENS_PER_DAY).toString()) }
    var output by remember(binding) { mutableStateOf((binding?.maxOutputTokens ?: AiBinding.DEFAULT_MAX_OUTPUT_TOKENS).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    ModelChoice(options, model) { model = it }
    SettingsGroup {
        for ((label, value, set) in listOf(Triple(AppStrings.aiCallsPerDay, calls) { v: String -> calls = v },
                Triple(AppStrings.aiTokensPerDay, tokens) { v: String -> tokens = v }, Triple(AppStrings.aiMaxOutputTokens, output) { v: String -> output = v })) {
            OutlinedTextField(value, { set(it.filter(Char::isDigit)); error = null }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        SettingsAction(AppStrings.save) {
            val chosen = model ?: run { error = AppStrings.noModelGateway; return@SettingsAction }
            val updated = runCatching { AiBinding(chosen.gatewayId, chosen.model, calls.toInt(), tokens.toLong(), output.toInt()) }.getOrNull()
                ?: run { error = AppStrings.aiLimitsOutOfRange; return@SettingsAction }
            scope.launch {
                try { platform.setAiBinding(programId, updated); editing = false; revision++ }
                catch (failure: IllegalArgumentException) { error = failure.message }
            }
        }
    }
    error?.let { SettingsCaption(it, error = true) }
}
