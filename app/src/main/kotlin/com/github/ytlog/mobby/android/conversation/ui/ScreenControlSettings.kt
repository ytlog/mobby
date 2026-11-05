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
    statusOverride: ScreenSetupStatus? = null, settingsOverride: ((ScreenSettingsTarget) -> Unit)? = null,
    onFinished: () -> Unit = onBack, embedded: Boolean = false, stepOffset: Int = 0) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var history by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(arrayListOf(ScreenSetupStep.MODE.name).apply { if (controller.state.value.adbPairing) add(ScreenSetupStep.PAIR.name) })
    }
    val step = ScreenSetupStep.valueOf(history.last())
    var selectedMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.mode) }
    var family by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(DeviceSettingsFamily.detect()) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectionPort by remember { mutableStateOf("") }
    var pendingCheck by remember { mutableStateOf<ScreenSetupStep?>(null) }
    var developerConfirmed by remember { mutableStateOf(false) }
    var wirelessConfirmed by remember { mutableStateOf(false) }
    var openWirelessAfterDeveloper by remember { mutableStateOf(false) }
    var forcePair by remember { mutableStateOf(false) }
    var connectAfterPairing by remember { mutableStateOf(false) }
    var pairingSuccessAtStart by remember { mutableLongStateOf(0) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val status = statusOverride ?: rememberScreenSetupStatus(permissionRefresh, pairing = state.mode == ScreenMode.ADB)
    fun freshStatus() = statusOverride ?: ScreenSetupStatus.read(context, pairing = controller.state.value.mode == ScreenMode.ADB)
    fun go(target: ScreenSetupStep) {
        focus.clearFocus()
        keyboard?.hide()
        error = null
        pendingCheck = null
        if (target.name != history.last()) history = ArrayList(history + target.name)
    }
    fun next(after: ScreenSetupStep): ScreenSetupStep {
        val path = screenSetupPath(controller.state.value.mode)
        return path.drop(path.indexOf(after) + 1).firstOrNull {
            !screenStepComplete(it, controller.state.value, freshStatus(), forcePair, developerConfirmed, wirelessConfirmed)
        } ?: ScreenSetupStep.COMPLETE
    }
    fun execute(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: AppStrings.screenBackendConnectionFailed }
            finally { busy = false }
        }
    }
    fun previous() {
        focus.clearFocus()
        keyboard?.hide()
        if (step == ScreenSetupStep.DEVELOPER || step == ScreenSetupStep.WIRELESS) openWirelessAfterDeveloper = false
        if (!step.auxiliary) {
            pendingCheck = null
            connectAfterPairing = false
            operation?.cancel()
            if (controller.state.value.adbPairing) {
                try { controller.cancelAdbPairing() }
                catch (failure: Exception) { error = failure.message ?: AppStrings.adbPairingFailed; return }
            }
            if (step == ScreenSetupStep.PAIR) forcePair = false
        }
        error = null
        if (history.size > 1) history = ArrayList(history.dropLast(1)) else onBack()
    }
    androidx.activity.compose.BackHandler { previous() }
    fun open(target: ScreenSettingsTarget) {
        pendingCheck = step.takeIf { it in setOf(ScreenSetupStep.NOTIFICATIONS, ScreenSetupStep.ACCESSIBILITY,
            ScreenSetupStep.WIFI, ScreenSetupStep.DEVELOPER, ScreenSetupStep.WIRELESS) }
        if (settingsOverride != null) settingsOverride(target)
        else if (!openScreenSettings(context, target, pairing = state.mode == ScreenMode.ADB)) {
            pendingCheck = null
            error = AppStrings.screenSettingsUnavailable
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRefresh++
        if (!granted) error = AppStrings.adbPairingNotifications
        else if (!ScreenSetupStatus.read(context, pairing = state.mode == ScreenMode.ADB).notificationsAllowed) open(ScreenSettingsTarget.NOTIFICATIONS)
    }
    fun notifications() {
        pendingCheck = ScreenSetupStep.NOTIFICATIONS
        if (status.sdk >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else open(ScreenSettingsTarget.NOTIFICATIONS)
    }
    fun connect() = execute {
        if (state.mode == ScreenMode.ADB) controller.setAdbPort(connectionPort)
        try {
            controller.connect()
            check(controller.state.value.connected) { AppStrings.screenBackendConnectionFailed }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (controller.state.value.mode == ScreenMode.ADB) {
                if (freshStatus().developerEnabled != true) {
                    openWirelessAfterDeveloper = true
                    go(ScreenSetupStep.DEVELOPER)
                }
                else open(ScreenSettingsTarget.WIRELESS)
            }
            error = failure.message ?: AppStrings.screenBackendConnectionFailed
            return@execute
        }
        go(ScreenSetupStep.COMPLETE)
    }
    fun pair() {
        if (state.adbPairing) { open(ScreenSettingsTarget.WIRELESS); return }
        if (!status.notificationsAllowed) { go(ScreenSetupStep.NOTIFICATIONS); return }
        execute {
            controller.startAdbPairing()
            pairingSuccessAtStart = controller.state.value.adbPairingSuccess
            connectAfterPairing = true
            open(ScreenSettingsTarget.WIRELESS)
        }
    }
    LaunchedEffect(step, pendingCheck, status) {
        if (pendingCheck == step && status.resumed && screenStepComplete(step, controller.state.value, status, forcePair, developerConfirmed, wirelessConfirmed)) {
            pendingCheck = null
            val target = next(step)
            if (step == ScreenSetupStep.DEVELOPER && target != ScreenSetupStep.WIRELESS) openWirelessAfterDeveloper = false
            go(target)
        }
    }
    LaunchedEffect(step, openWirelessAfterDeveloper) {
        if (step == ScreenSetupStep.WIRELESS && openWirelessAfterDeveloper) {
            openWirelessAfterDeveloper = false
            open(ScreenSettingsTarget.WIRELESS)
        }
    }
    LaunchedEffect(state.adbPairing, state.adbPairingSuccess, state.error, state.mode, state.connected, state.active, status.resumed, busy, connectAfterPairing) {
        if (connectAfterPairing) {
            val current = controller.state.value
            if (!current.adbPairing) {
                if (current.error != null || current.mode != ScreenMode.ADB || current.active || current.adbPairingSuccess <= pairingSuccessAtStart) {
                    connectAfterPairing = false
                    error = current.error ?: AppStrings.wizardPairCancelled
                } else if (status.resumed && !busy) {
                    connectAfterPairing = false
                    forcePair = false
                    go(ScreenSetupStep.CONNECT)
                    if (!current.connected) connect() else go(ScreenSetupStep.COMPLETE)
                }
            }
        }
    }
    val complete = screenStepComplete(step, state, status, forcePair, developerConfirmed, wirelessConfirmed)
    val ready = status.notificationsAllowed && if (state.mode == ScreenMode.CURRENT) status.accessibilityEnabled else state.connected
    val heading = when (step) {
        ScreenSetupStep.MODE -> AppStrings.wizardChooseMode
        ScreenSetupStep.NOTIFICATIONS -> AppStrings.wizardNotifications
        ScreenSetupStep.ACCESSIBILITY -> AppStrings.openAccessibilitySettings
        ScreenSetupStep.WIFI -> AppStrings.screenOpenWifi
        ScreenSetupStep.DEVELOPER -> AppStrings.screenDeveloperStep
        ScreenSetupStep.WIRELESS -> AppStrings.screenWirelessStep
        ScreenSetupStep.PAIR -> AppStrings.screenPairStep
        ScreenSetupStep.CONNECT -> AppStrings.connectScreen
        ScreenSetupStep.PORT -> AppStrings.screenManualPort
        ScreenSetupStep.BRANDS -> AppStrings.screenOtherBrands
        ScreenSetupStep.PAIR_HELP -> AppStrings.screenNoNotificationReply
        ScreenSetupStep.DISCONNECT -> AppStrings.disconnectScreen
        ScreenSetupStep.COMPLETE -> if (ready) AppStrings.wizardReady else AppStrings.screenGuideTitle
    }
    val description = when (step) {
        ScreenSetupStep.MODE -> AppStrings.wizardChooseModeHelp
        ScreenSetupStep.NOTIFICATIONS -> AppStrings.wizardNotificationsHelp
        ScreenSetupStep.ACCESSIBILITY -> AppStrings.currentScreenModeHelp
        ScreenSetupStep.WIFI -> AppStrings.wizardWifiHelp
        ScreenSetupStep.DEVELOPER -> AppStrings.wizardDeveloperHelp
        ScreenSetupStep.WIRELESS -> AppStrings.wizardWirelessHelp
        ScreenSetupStep.PAIR -> AppStrings.wizardPairHelp
        ScreenSetupStep.CONNECT -> if (state.mode == ScreenMode.ROOT) AppStrings.screenRootGuide else AppStrings.wizardConnectHelp
        ScreenSetupStep.PORT -> AppStrings.adbConnectionPortHelp
        ScreenSetupStep.PAIR_HELP -> if (family == DeviceSettingsFamily.XIAOMI) AppStrings.screenPairXiaomiHelp else AppStrings.screenPairHelp
        ScreenSetupStep.COMPLETE -> if (ready) AppStrings.wizardReadyHelp else AppStrings.screenCheckOnReturn
        ScreenSetupStep.DISCONNECT -> AppStrings.screenBackendReady
        ScreenSetupStep.BRANDS -> ""
    }
    val primaryText = when {
        step == ScreenSetupStep.COMPLETE && ready -> AppStrings.wizardFinish
        step.auxiliary && step != ScreenSetupStep.DISCONNECT -> AppStrings.wizardNext
        step == ScreenSetupStep.DISCONNECT -> AppStrings.disconnectScreen
        complete || step == ScreenSetupStep.MODE -> AppStrings.wizardNext
        step == ScreenSetupStep.NOTIFICATIONS -> AppStrings.wizardNotifications
        step == ScreenSetupStep.ACCESSIBILITY -> AppStrings.openAccessibilitySettings
        step == ScreenSetupStep.WIFI -> AppStrings.screenOpenWifi
        step == ScreenSetupStep.DEVELOPER -> AppStrings.screenAboutDevice
        step == ScreenSetupStep.WIRELESS -> AppStrings.screenOpenWireless
        step == ScreenSetupStep.PAIR -> if (state.adbPairing) AppStrings.screenOpenWireless else AppStrings.adbStartPairing
        else -> AppStrings.connectScreen
    }
    fun primary() {
        when {
            step == ScreenSetupStep.MODE -> execute {
                check(!state.active && !state.adbPairing) { AppStrings.screenModeBusy }
                if (selectedMode != state.mode) { controller.select(selectedMode); connectionPort = "" }
                go(next(ScreenSetupStep.MODE))
            }
            step == ScreenSetupStep.PORT -> execute { controller.setAdbPort(connectionPort); previous() }
            step == ScreenSetupStep.BRANDS || step == ScreenSetupStep.PAIR_HELP -> previous()
            step == ScreenSetupStep.DISCONNECT -> execute {
                controller.disconnect()
                history = arrayListOf(ScreenSetupStep.MODE.name, ScreenSetupStep.CONNECT.name)
            }
            step == ScreenSetupStep.COMPLETE -> if (ready) onFinished() else go(next(ScreenSetupStep.MODE))
            complete -> go(next(step))
            step == ScreenSetupStep.NOTIFICATIONS -> notifications()
            step == ScreenSetupStep.ACCESSIBILITY -> open(ScreenSettingsTarget.ACCESSIBILITY)
            step == ScreenSetupStep.WIFI -> open(ScreenSettingsTarget.WIFI)
            step == ScreenSetupStep.DEVELOPER -> open(ScreenSettingsTarget.ABOUT)
            step == ScreenSetupStep.WIRELESS -> open(ScreenSettingsTarget.WIRELESS)
            step == ScreenSetupStep.PAIR -> pair()
            step == ScreenSetupStep.CONNECT -> connect()
        }
    }
    val enabled = !state.active && (step != ScreenSetupStep.MODE || !state.adbPairing) &&
        (step !in setOf(ScreenSetupStep.CONNECT, ScreenSetupStep.PORT, ScreenSetupStep.DISCONNECT) || !state.adbPairing) &&
        (step != ScreenSetupStep.MODE || selectedMode == ScreenMode.CURRENT || status.sdk >= 33) &&
        (step != ScreenSetupStep.DISCONNECT || state.connected)
    val chrome = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
    Box(chrome) {
        SetupWizardFrame(if (embedded) AppStrings.setupGuide else AppStrings.screenModeTitle,
            stepOffset + history.count { !ScreenSetupStep.valueOf(it).auxiliary }, heading, description,
            back = ::previous, primaryText = primaryText, primary = ::primary, enabled = enabled, busy = busy,
            secondaryText = if (state.adbPairing && !step.auxiliary) AppStrings.adbCancelPairing else if (step == ScreenSetupStep.MODE && !embedded) AppStrings.back else AppStrings.wizardPrevious,
            error = error ?: state.error ?: if (state.active) AppStrings.screenModeBusy else null) {
            when (step) {
                ScreenSetupStep.MODE -> {
                    SettingsGroup {
                        ScreenMode.entries.forEachIndexed { index, mode ->
                            if (index > 0) GroupDivider()
                            ChoiceRow(screenModeLabel(mode), selectedMode == mode, { selectedMode = mode }, enabled = !busy && !state.active && !state.adbPairing)
                        }
                    }
                    Text(when (selectedMode) { ScreenMode.CURRENT -> AppStrings.currentScreenModeHelp; ScreenMode.ADB -> AppStrings.virtualScreenModeHelp; ScreenMode.ROOT -> AppStrings.screenRootGuide },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedMode != ScreenMode.CURRENT && status.sdk < 33) Text(AppStrings.virtualScreenAndroidVersion, color = MaterialTheme.colorScheme.error)
                    if (embedded) TextButton(onClick = onFinished, enabled = !state.active && !state.adbPairing) { Text(AppStrings.wizardSkipScreen) }
                }
                ScreenSetupStep.DEVELOPER -> {
                    Text(family.label(), style = MaterialTheme.typography.labelLarge)
                    Text(family.instructions(), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { go(ScreenSetupStep.BRANDS) }) { Text(AppStrings.screenOtherBrands) }
                    if (status.developerEnabled == null) TextButton(onClick = { developerConfirmed = true; go(next(step)) }) { Text(AppStrings.wizardConfirmEnabled) }
                }
                ScreenSetupStep.WIRELESS -> if (status.wirelessEnabled == null) TextButton(onClick = { wirelessConfirmed = true; go(next(step)) }) { Text(AppStrings.wizardConfirmEnabled) }
                ScreenSetupStep.BRANDS -> SettingsGroup {
                    DeviceSettingsFamily.entries.forEachIndexed { index, candidate ->
                        if (index > 0) GroupDivider()
                        ChoiceRow(candidate.label(), candidate == family, { family = candidate })
                    }
                }
                ScreenSetupStep.PAIR -> TextButton(onClick = { go(ScreenSetupStep.PAIR_HELP) }) { Text(AppStrings.screenNoNotificationReply) }
                ScreenSetupStep.CONNECT -> {
                    Text(if (state.connected) AppStrings.screenBackendReady else AppStrings.screenBackendDisconnected, style = MaterialTheme.typography.bodyMedium)
                    if (state.mode == ScreenMode.ADB) {
                        TextButton(onClick = { go(ScreenSetupStep.PORT) }, enabled = enabled && !busy) { Text(AppStrings.screenManualPort) }
                        if (error != null || state.error != null) TextButton(onClick = { forcePair = true; go(ScreenSetupStep.PAIR) }, enabled = enabled && !busy) { Text(AppStrings.screenPairAgain) }
                    }
                }
                ScreenSetupStep.PORT -> SettingsGroup { SettingsField(connectionPort, { connectionPort = it }, AppStrings.adbConnectionPort,
                    enabled = enabled && !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                ScreenSetupStep.COMPLETE -> if (state.mode != ScreenMode.CURRENT && state.connected) TextButton(onClick = { go(ScreenSetupStep.DISCONNECT) }, enabled = enabled) { Text(AppStrings.disconnectScreen) }
                else -> Unit
            }
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
