package com.libtermux.os.gui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.libtermux.os.distro.Distro
import com.libtermux.os.registry.DisplayResolution
import com.libtermux.os.registry.SupportedDistro
import com.libtermux.os.settings.DistroRuntimeSettings
import com.libtermux.os.settings.DistroSettingsStore
import kotlinx.coroutines.launch

/**
 * Full settings screen for a single distro.
 *
 * Settings are persisted via DataStore. Changes take effect on the next
 * session start — the current session is not affected.
 *
 * Usage:
 * ```kotlin
 * DistroSettingsScreen(
 *     distro          = Distro.Kali,
 *     supportedDistro = registry[Distro.Kali]!!,
 *     onBack          = { navController.popBackStack() },
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistroSettingsScreen(
    distro:          Distro,
    supportedDistro: SupportedDistro,
    onBack:          () -> Unit,
    modifier:        Modifier = Modifier,
) {
    val context      = LocalContext.current
    val store        = remember { DistroSettingsStore(context) }
    val coroutineScope = rememberCoroutineScope()

    val settings by store.getSettings(distro, supportedDistro).collectAsState(
        initial = DistroRuntimeSettings(distroId = distro.id)
    )

    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${distro.displayName} Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("Reset")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->

        LazyColumn(
            contentPadding = PaddingValues(
                start  = 16.dp,
                end    = 16.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // ── Display ───────────────────────────────────────────────────
            item { SectionHeader("Display") }

            item {
                SettingsDropdown(
                    label   = "Resolution",
                    current = settings.resolutionLabel,
                    options = DisplayResolution.entries
                        .filter { it != DisplayResolution.CUSTOM }
                        .map { it.label },
                    onSelect = { label ->
                        val preset = DisplayResolution.entries.first { it.label == label }
                        coroutineScope.launch {
                            store.update(distro) {
                                it.copy(displayWidth = preset.width, displayHeight = preset.height)
                            }
                        }
                    },
                )
            }

            item {
                SettingsDropdown(
                    label   = "Color depth",
                    current = "${settings.colorDepth} bit",
                    options = listOf("16 bit", "24 bit", "32 bit"),
                    onSelect = { label ->
                        val depth = label.split(" ").first().toIntOrNull() ?: 24
                        coroutineScope.launch {
                            store.update(distro) { it.copy(colorDepth = depth) }
                        }
                    },
                )
            }

            item {
                SettingsSwitch(
                    label    = "Scale to fit screen",
                    checked  = settings.scaleToFit,
                    onChange = { v ->
                        coroutineScope.launch {
                            store.update(distro) { it.copy(scaleToFit = v) }
                        }
                    },
                )
            }

            // ── VNC ───────────────────────────────────────────────────────
            item { SectionHeader("VNC Connection") }

            item {
                SettingsTextField(
                    label   = "VNC Port",
                    value   = settings.vncPort.toString(),
                    keyboardType = KeyboardType.Number,
                    onSave  = { v ->
                        val port = v.toIntOrNull() ?: return@SettingsTextField
                        coroutineScope.launch {
                            store.update(distro) { it.copy(vncPort = port) }
                        }
                    },
                )
            }

            item {
                SettingsTextField(
                    label       = "VNC Password (empty = no auth)",
                    value       = settings.vncPassword,
                    isPassword  = true,
                    onSave      = { v ->
                        coroutineScope.launch {
                            store.update(distro) { it.copy(vncPassword = v) }
                        }
                    },
                )
            }

            // ── Session ───────────────────────────────────────────────────
            item { SectionHeader("Session") }

            item {
                SettingsTextField(
                    label  = "Startup commands (one per line)",
                    value  = settings.startupCmds.joinToString("\n"),
                    singleLine = false,
                    onSave = { v ->
                        val cmds = v.lines().filter { it.isNotBlank() }
                        coroutineScope.launch {
                            store.update(distro) { it.copy(startupCmds = cmds) }
                        }
                    },
                )
            }

            item {
                SettingsSwitch(
                    label    = "Show toolbar",
                    checked  = settings.showToolbar,
                    onChange = { v ->
                        coroutineScope.launch {
                            store.update(distro) { it.copy(showToolbar = v) }
                        }
                    },
                )
            }

            item {
                SettingsSwitch(
                    label    = "Vibrate on mouse click",
                    checked  = settings.vibrateMouse,
                    onChange = { v ->
                        coroutineScope.launch {
                            store.update(distro) { it.copy(vibrateMouse = v) }
                        }
                    },
                )
            }

            // ── Info ──────────────────────────────────────────────────────
            item { SectionHeader("Info") }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        InfoRow("Distro",   distro.displayName)
                        InfoRow("Rootfs",   distro.rootfsUrl.substringAfterLast('/'))
                        InfoRow("Shell",    distro.defaultShell)
                        if (supportedDistro.guiEnabled) {
                            InfoRow("Desktop", supportedDistro.desktopEnvironment.displayName)
                            if (supportedDistro.isGnomeBased) {
                                InfoRow("Renderer", "Mesa LLVMpipe (software)")
                                InfoRow("Est. size", "~${supportedDistro.desktopEnvironment.approximateSizeMb} MB")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title  = { Text("Reset to defaults?") },
            text   = { Text("All settings for ${distro.displayName} will be reset.") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch { store.reset(distro) }
                    showResetDialog = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Settings components ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value          = current,
                onValueChange  = {},
                readOnly       = true,
                trailingIcon   = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier       = Modifier
                    .menuAnchor()
                    .width(180.dp),
                textStyle      = MaterialTheme.typography.bodySmall,
                singleLine     = true,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text    = { Text(option, style = MaterialTheme.typography.bodySmall) },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }

    OutlinedTextField(
        value          = text,
        onValueChange  = { text = it },
        label          = { Text(label) },
        singleLine     = singleLine,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier        = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        trailingIcon    = {
            if (text != value) {
                TextButton(onClick = { onSave(text) }) { Text("Save") }
            }
        },
    )
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(key, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
