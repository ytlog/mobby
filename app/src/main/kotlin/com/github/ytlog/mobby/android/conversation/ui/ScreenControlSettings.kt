package com.github.ytlog.mobby.android.conversation.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.ytlog.mobby.android.MobbyApplication
import com.github.ytlog.mobby.android.conversation.domain.Appearance
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import kotlinx.coroutines.*
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal fun screenModeLabel(mode: ScreenMode): String = when (mode) {
    ScreenMode.CURRENT -> AppStrings.currentScreenMode
    ScreenMode.ADB -> AppStrings.adbScreenMode
    ScreenMode.ROOT -> AppStrings.rootScreenMode
}

/** Installed by the application composition root; UI never acquires privileges directly. */
object ScreenUi { var controller: ScreenControlPort? = null }

class ScreenControlSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by (application as MobbyApplication).conversations.appearance.collectAsStateWithLifecycle()
            val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
            LaunchedEffect(dark) {
                val background = mobbySystemBarColor(dark)
                val style = if (dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background, background)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme) {
                Surface(Modifier.fillMaxSize()) {
                    ScreenUi.controller?.let { ScreenControlSettings(it, onBack = ::finish) }
                }
            }
        }
    }
}

@Composable internal fun ScreenControlSettings(controller: ScreenControlPort, onBack: () -> Unit,
    statusOverride: ScreenSetupStatus? = null, settingsOverride: ((ScreenSettingsTarget) -> Unit)? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectionPort by remember(state.mode) { mutableStateOf("") }
    var manualPort by remember(state.mode) { mutableStateOf(false) }
    // A live user-started pairing may connect once on return. Never restore or re-arm this after failure.
    var connectAfterPairing by remember { mutableStateOf(false) }
    var pairingSuccessAtStart by remember { mutableLongStateOf(0) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val status = statusOverride ?: rememberScreenSetupStatus(permissionRefresh, pairing = state.mode == ScreenMode.ADB)
    var afterNotificationPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRefresh++
        val next = afterNotificationPermission
        afterNotificationPermission = null
        if (!granted) error = AppStrings.adbPairingNotifications
        else if (ScreenSetupStatus.read(context, pairing = state.mode == ScreenMode.ADB).notificationsAllowed) next?.invoke()
        else if (!openScreenSettings(context, ScreenSettingsTarget.NOTIFICATIONS, pairing = state.mode == ScreenMode.ADB)) error = AppStrings.screenSettingsUnavailable
    }
    fun open(target: ScreenSettingsTarget) {
        if (settingsOverride != null) settingsOverride(target)
        else if (!openScreenSettings(context, target, pairing = state.mode == ScreenMode.ADB)) error = AppStrings.screenSettingsUnavailable
    }
    fun notifications(next: () -> Unit = {}) {
        if (status.sdk >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            afterNotificationPermission = next
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (!status.notificationsAllowed) open(ScreenSettingsTarget.NOTIFICATIONS)
        else next()
    }
    fun execute(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: AppStrings.screenBackendConnectionFailed }
            finally { busy = false }
        }
    }
    fun connect() = execute {
        try {
            if (state.mode == ScreenMode.ADB) controller.setAdbPort(connectionPort)
            controller.connect()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (state.mode == ScreenMode.ADB) manualPort = true; throw failure }
    }
    fun pair() {
        if (state.adbPairing) {
            connectAfterPairing = false
            execute { controller.cancelAdbPairing() }
        } else notifications {
            execute {
                controller.startAdbPairing()
                pairingSuccessAtStart = controller.state.value.adbPairingSuccess
                connectAfterPairing = true
                open(ScreenSettingsTarget.WIRELESS)
            }
        }
    }
    LaunchedEffect(state.adbPairing, state.adbPairingSuccess, state.error, state.mode, state.connected, state.active, status.resumed, busy, connectAfterPairing) {
        if (connectAfterPairing) {
            val current = controller.state.value
            if (!current.adbPairing) {
                if (current.error != null || current.mode != ScreenMode.ADB || current.connected || current.active || current.adbPairingSuccess <= pairingSuccessAtStart) connectAfterPairing = false
                else if (current.adbPaired && status.resumed && !busy) {
                    connectAfterPairing = false
                    connect()
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeader(AppStrings.screenModeTitle, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup {
                ScreenMode.entries.forEachIndexed { index, mode ->
                    if (index > 0) GroupDivider()
                    ChoiceRow(screenModeLabel(mode), state.mode == mode, { execute { controller.select(mode) } },
                        enabled = !busy && !state.active && !state.adbPairing)
                }
            }
            if (state.mode == ScreenMode.CURRENT) {
                SettingsCaption(AppStrings.currentScreenQuickHelp)
                if (status.accessibilityEnabled) SettingsCaption(AppStrings.screenAccessReady)
                SettingsGroup {
                    SettingsItem(AppStrings.openAccessibilitySettings, { open(ScreenSettingsTarget.ACCESSIBILITY) },
                        if (status.accessibilityEnabled) AppStrings.stepCompleted else AppStrings.currentScreenModeHelp)
                }
            } else {
                SettingsCaption(AppStrings.virtualScreenModeHelp)
                SettingsCaption(if (state.connected) AppStrings.screenBackendReady else AppStrings.screenBackendDisconnected)
                val savedPairReady = state.mode == ScreenMode.ADB && state.adbPaired && !state.adbPairing && status.notificationsAllowed &&
                    status.wifiConnected && status.developerEnabled != false && status.wirelessEnabled != false
                if (status.sdk < 33) SettingsCaption(AppStrings.virtualScreenAndroidVersion, error = true)
                else if (state.mode == ScreenMode.ADB && !savedPairReady) {
                    AdbSetupGuide(state, status, enabled = !busy && !state.active, open = ::open, notifications = { notifications() }, pair = ::pair)
                } else if (state.mode == ScreenMode.ROOT) SettingsCaption(AppStrings.screenRootGuide)
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(22.dp),
                    enabled = !busy && !state.active && !state.adbPairing && status.sdk >= 33, onClick = {
                        connectAfterPairing = false
                        if (state.connected) execute { controller.disconnect() } else connect()
                    }) { Text(if (state.connected) AppStrings.disconnectScreen else AppStrings.connectScreen) }
                if (state.mode == ScreenMode.ADB && status.sdk >= 33) {
                    SettingsGroup {
                        SettingsItem(AppStrings.screenManualPort, { manualPort = !manualPort }, AppStrings.screenAutomaticPortHelp)
                        if (manualPort) {
                            GroupDivider()
                            SettingsField(connectionPort, { connectionPort = it }, AppStrings.adbConnectionPort,
                                enabled = !busy && !state.active && !state.connected && !state.adbPairing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    if (manualPort) SettingsCaption(AppStrings.adbConnectionPortHelp)
                    if (savedPairReady) AdbSetupGuide(state, status, enabled = !busy && !state.active,
                        open = ::open, notifications = { notifications() }, pair = ::pair, compact = true)
                }
            }
            SettingsGroup {
                SettingsItem(if (status.notificationsAllowed) AppStrings.openNotificationSettings else AppStrings.screenAllowNotifications,
                    { if (status.notificationsAllowed) open(ScreenSettingsTarget.NOTIFICATIONS) else notifications() },
                    if (status.notificationsAllowed) AppStrings.screenNotificationsReady else AppStrings.screenNotificationHelp)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            (error ?: state.error)?.let { SettingsCaption(it, error = true) }
        }
    }
}

@Composable internal fun VirtualScreenPreview() {
    val controller = ScreenUi.controller ?: return
    val state by controller.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(true) }
    if (!state.active) return
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.displayId, expanded, lifecycle) {
        if (expanded) lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (isActive) {
                try { controller.refreshPreview() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { break }
                delay(700)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AppStrings.virtualScreenPreview, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) AppStrings.hideVirtualScreen else AppStrings.showVirtualScreen) }
        }
        if (expanded) {
            val bitmap = remember(state.jpeg) { state.jpeg?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
            if (bitmap != null) Image(bitmap, AppStrings.virtualScreenPreview, Modifier.fillMaxWidth().heightIn(max = 240.dp))
            else Text(AppStrings.virtualScreenWaiting, style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
