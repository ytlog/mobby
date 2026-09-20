package com.libtermux.os.gui.compose

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libtermux.os.OsEnvironment
import com.libtermux.os.distro.Distro
import com.libtermux.os.distro.DistroSetupState
import com.libtermux.os.registry.DesktopEnvironment
import com.libtermux.os.registry.SupportedDistro
import com.libtermux.os.settings.DistroRuntimeSettings
import kotlinx.coroutines.launch

/**
 * Distro selection and management UI.
 *
 * Shows only the distros declared in [DistroRegistry] — not all available
 * distros. Each card shows install status and offers Install / Launch /
 * Settings actions.
 *
 * Usage:
 * ```kotlin
 * DistroLauncher(
 *     os       = libtermux.os,
 *     onLaunch = { session -> navController.navigate("desktop") },
 * )
 * ```
 */
@Composable
fun DistroLauncher(
    os: OsEnvironment,
    modifier: Modifier = Modifier,
    onLaunch: ((com.libtermux.os.gui.DesktopSession) -> Unit)? = null,
    onOpenSettings: ((Distro) -> Unit)? = null,
) {
    val supportedDistros = os.registry.launcherEntries
    val coroutineScope   = rememberCoroutineScope()

    // Per-distro install progress
    val installStates = remember {
        mutableStateMapOf<String, DistroSetupState?>()
    }

    LazyColumn(
        modifier            = modifier.fillMaxSize(),
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text  = "Linux Environments",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text  = "Mode: ${os.executionMode.name}${if (os.isUsingRealChroot) " (Root)" else " (No-root)"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        items(supportedDistros, key = { it.distro.id }) { entry ->
            val isInstalled = os.isInstalled(entry.distro)
            val installState = installStates[entry.distro.id]

            DistroCard(
                entry        = entry,
                isInstalled  = isInstalled,
                installState = installState,
                onInstall    = {
                    coroutineScope.launch {
                        os.setupDistro(entry.distro).collect { state ->
                            installStates[entry.distro.id] = state
                        }
                    }
                },
                onLaunch = {
                    if (entry.guiEnabled && onLaunch != null) {
                        val settings = DistroRuntimeSettings(
                            distroId      = entry.distro.id,
                            displayWidth  = entry.effectiveWidth,
                            displayHeight = entry.effectiveHeight,
                            colorDepth    = entry.colorDepth,
                            vncPort       = entry.vncPort,
                            vncPassword   = entry.vncPassword ?: "",
                        )
                        coroutineScope.launch {
                            val session = os.createDesktopSession(entry.distro)
                            session.start(settings)
                            onLaunch(session)
                        }
                    }
                },
                onOpenSettings = { onOpenSettings?.invoke(entry.distro) },
                onUninstall    = {
                    coroutineScope.launch { os.uninstall(entry.distro) }
                },
            )
        }
    }
}

// ── Distro card ───────────────────────────────────────────────────────────────

@Composable
private fun DistroCard(
    entry: SupportedDistro,
    isInstalled: Boolean,
    installState: DistroSetupState?,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onOpenSettings: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showUninstallDialog by remember { mutableStateOf(false) }

    val isInstalling = installState != null &&
        installState !is DistroSetupState.Completed &&
        installState !is DistroSetupState.Failed &&
        installState !is DistroSetupState.AlreadyInstalled

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header ────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                DistroIcon(distro = entry.distro)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = entry.distro.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text  = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                StatusBadge(isInstalled = isInstalled, isInstalling = isInstalling)
            }

            // ── Capability chips ──────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.guiEnabled) {
                    CapabilityChip(label = "GUI • ${entry.desktopEnvironment.displayName}")
                }
                CapabilityChip(label = "${entry.effectiveWidth}×${entry.effectiveHeight}")
                if (entry.desktopEnvironment.approximateSizeMb >= 1000) {
                    CapabilityChip(
                        label = "~${entry.desktopEnvironment.approximateSizeMb / 1000} GB",
                        isWarning = true,
                    )
                }
            }

            // ── Install progress ──────────────────────────────────────────
            AnimatedVisibility(visible = isInstalling) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    val (label, progress) = when (val s = installState) {
                        is DistroSetupState.Downloading ->
                            "Downloading… ${(s.progress * 100).toInt()}%" to s.progress
                        is DistroSetupState.Extracting  ->
                            "Extracting… ${(s.progress * 100).toInt()}%" to s.progress
                        is DistroSetupState.InstallingProot -> "Installing proot…" to null
                        is DistroSetupState.ConfiguringDistro -> "Configuring: ${s.step}" to null
                        else -> "Working…" to null
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    if (progress != null)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            // ── Error ─────────────────────────────────────────────────────
            if (installState is DistroSetupState.Failed) {
                Text(
                    text  = "Error: ${installState.reason}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ── Actions ───────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!isInstalled) {
                    Button(
                        onClick  = onInstall,
                        enabled  = !isInstalling,
                        modifier = Modifier.weight(1f),
                    ) { Text("Install") }
                } else {
                    if (entry.guiEnabled) {
                        Button(
                            onClick  = onLaunch,
                            modifier = Modifier.weight(1f),
                        ) { Text("Launch Desktop") }
                    } else {
                        Button(
                            onClick  = onLaunch,
                            modifier = Modifier.weight(1f),
                        ) { Text("Open Terminal") }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showUninstallDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Uninstall",
                             tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title  = { Text("Uninstall ${entry.distro.displayName}?") },
            text   = { Text("This will delete the rootfs. All data inside will be lost.") },
            confirmButton  = {
                TextButton(onClick = { onUninstall(); showUninstallDialog = false }) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton  = {
                TextButton(onClick = { showUninstallDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun DistroIcon(distro: Distro) {
    val (emoji, bg) = when (distro) {
        Distro.Kali       -> "🐉" to Color(0xFF2E7D32)
        Distro.Ubuntu2404,
        Distro.Ubuntu2204 -> "🟠" to Color(0xFFE65100)
        Distro.Debian12   -> "🌀" to Color(0xFF1565C0)
        Distro.Alpine     -> "🏔️" to Color(0xFF0277BD)
        Distro.Fedora40   -> "🎩" to Color(0xFF283593)
        else              -> "🐧" to Color(0xFF424242)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg.copy(alpha = 0.15f))
            .border(1.dp, bg.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
    ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun StatusBadge(isInstalled: Boolean, isInstalling: Boolean) {
    val (label, color) = when {
        isInstalling -> "Installing" to MaterialTheme.colorScheme.tertiary
        isInstalled  -> "Ready"      to MaterialTheme.colorScheme.primary
        else         -> "Not installed" to MaterialTheme.colorScheme.outline
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text  = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CapabilityChip(label: String, isWarning: Boolean = false) {
    val bg    = if (isWarning) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
    val color = if (isWarning) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall,
            color    = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
