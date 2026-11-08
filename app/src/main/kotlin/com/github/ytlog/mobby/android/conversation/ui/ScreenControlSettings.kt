package com.github.ytlog.mobby.android.conversation.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                val style = mobbySystemBarStyle(dark)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme, typography = MobbyTypography) {
                Surface(Modifier.fillMaxSize()) {
                    ScreenUi.controller?.let { ScreenControlSettings(it, onBack = ::finish, onFinished = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }) }
                    VirtualScreenPreviewLayer()
                }
            }
        }
    }
}

@Composable internal fun ScreenControlSettings(controller: ScreenControlPort, onBack: () -> Unit,
    statusOverride: ScreenSetupStatus? = null, settingsOverride: ((ScreenSettingsTarget) -> Unit)? = null,
    onFinished: () -> Unit = onBack, embedded: Boolean = false, stepOffset: Int = 0,
    canSelectPrefix: (Int) -> Boolean = { false }, selectPrefix: (Int) -> Unit = {},
    isPrefixComplete: (Int) -> Boolean = { false }) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val sdk = statusOverride?.sdk ?: Build.VERSION.SDK_INT
    val setup = viewModel<ScreenSetupViewModel>()
    val initialMode = if (ScreenAccessRouting.recommendsAdb(state, sdk)) ScreenMode.ADB else state.mode
    val draft = setup.draft(initialMode, state.adbPairing)
    var history by draft::history
    val step = ScreenSetupStep.valueOf(history.last())
    var selectedMode by draft::selectedMode
    var family by draft::family
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var error by draft::error
    var connectionPort by draft::connectionPort
    var pendingCheck by draft::pendingCheck
    var manualAdbSetup by draft::manualAdbSetup
    var developerConfirmed by draft::developerConfirmed
    var wirelessConfirmed by draft::wirelessConfirmed
    var openWirelessOnArrival by draft::openWirelessOnArrival
    var forcePair by draft::forcePair
    var connectAfterPairing by draft::connectAfterPairing
    var pairingSuccessAtStart by draft::pairingSuccessAtStart
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val status = statusOverride ?: rememberScreenSetupStatus(permissionRefresh, pairing = state.mode == ScreenMode.ADB)
    var rootCommandAvailable by remember(controller) { mutableStateOf(false) }
    var rootGranted by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, step, sdk, status.resumed) {
        if (!status.resumed) rootGranted = false
        if (step == ScreenSetupStep.MODE && sdk >= 33 && status.resumed) {
            rootCommandAvailable = controller.hasRootCommand()
            if (!rootCommandAvailable) rootGranted = false
        }
    }
    fun modeAvailable(mode: ScreenMode): Boolean = when (mode) {
        ScreenMode.CURRENT -> true
        ScreenMode.ADB -> sdk >= 33
        ScreenMode.ROOT -> sdk >= 33 && rootGranted
    }
    fun freshStatus() = statusOverride ?: ScreenSetupStatus.read(context, pairing = controller.state.value.mode == ScreenMode.ADB)
    fun go(target: ScreenSetupStep) {
        focus.clearFocus()
        keyboard?.hide()
        error = null
        pendingCheck = null
        if (target.name != history.last()) history = ArrayList(history + target.name)
    }
    fun next(after: ScreenSetupStep): ScreenSetupStep {
        val current = controller.state.value
        val path = screenSetupPath(current.mode)
        val permissions = freshStatus()
        return path.drop(path.indexOf(after) + 1).firstOrNull {
            val skipAccessibility = it == ScreenSetupStep.ACCESSIBILITY && current.mode == ScreenMode.ADB &&
                (manualAdbSetup || current.connected)
            !skipAccessibility && !screenStepComplete(it, current, permissions, forcePair, developerConfirmed, wirelessConfirmed)
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
    fun leave(action: () -> Unit) {
        setup.clear()
        action()
    }
    fun previous() {
        focus.clearFocus()
        keyboard?.hide()
        if (step == ScreenSetupStep.DEVELOPER || step == ScreenSetupStep.WIRELESS) openWirelessOnArrival = false
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
        if (history.size > 1) history = ArrayList(history.dropLast(1)) else leave(onBack)
    }
    androidx.activity.compose.BackHandler { previous() }
    fun open(target: ScreenSettingsTarget, assistNavigation: Boolean = false, openPairing: Boolean = false) {
        pendingCheck = step.takeIf { it in setOf(ScreenSetupStep.NOTIFICATIONS, ScreenSetupStep.ACCESSIBILITY,
            ScreenSetupStep.WIFI, ScreenSetupStep.DEVELOPER, ScreenSetupStep.WIRELESS) }
        if (settingsOverride != null) settingsOverride(target)
        else if (!ScreenAccessRouting.openSettings(context, target, pairing = state.mode == ScreenMode.ADB,
                assistNavigation = assistNavigation && status.accessibilityEnabled, openPairing = openPairing)) {
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
                    openWirelessOnArrival = true
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
        if (state.adbPairing) {
            open(ScreenSettingsTarget.WIRELESS, assistNavigation = true, openPairing = true)
            return
        }
        if (!status.notificationsAllowed) { go(ScreenSetupStep.NOTIFICATIONS); return }
        execute {
            controller.startAdbPairing()
            pairingSuccessAtStart = controller.state.value.adbPairingSuccess
            connectAfterPairing = true
            open(ScreenSettingsTarget.WIRELESS, assistNavigation = true, openPairing = true)
        }
    }
    LaunchedEffect(step, pendingCheck, status) {
        if (pendingCheck == step && status.resumed && screenStepComplete(step, controller.state.value, status, forcePair, developerConfirmed, wirelessConfirmed)) {
            pendingCheck = null
            val target = next(step)
            openWirelessOnArrival = target == ScreenSetupStep.WIRELESS && (openWirelessOnArrival || status.accessibilityEnabled)
            go(target)
        }
    }
    LaunchedEffect(step, openWirelessOnArrival, status.resumed) {
        if (step == ScreenSetupStep.WIRELESS && openWirelessOnArrival && status.resumed) {
            openWirelessOnArrival = false
            open(ScreenSettingsTarget.WIRELESS, assistNavigation = true)
        }
    }
    LaunchedEffect(state.adbPairing, state.adbPairingSuccess, state.error, state.mode, state.connected, state.active, status.resumed, busy, connectAfterPairing) {
        if (connectAfterPairing) {
            val current = controller.state.value
            if (!current.adbPairing) {
                if (current.mode != ScreenMode.ADB || current.active || current.adbPairingSuccess <= pairingSuccessAtStart) {
                    connectAfterPairing = false
                    error = current.error ?: AppStrings.wizardPairCancelled
                } else if (status.resumed && !busy) {
                    connectAfterPairing = false
                    forcePair = false
                    go(ScreenSetupStep.CONNECT)
                    if (current.connected) go(ScreenSetupStep.COMPLETE)
                    else if (current.error != null) error = current.error
                    else connect()
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
        ScreenSetupStep.ACCESSIBILITY -> if (state.mode == ScreenMode.ADB) AppStrings.wizardAdbAccessibilityHelp
            else AppStrings.currentScreenModeHelp
        ScreenSetupStep.WIFI -> AppStrings.wizardWifiHelp
        ScreenSetupStep.DEVELOPER -> AppStrings.wizardDeveloperHelp
        ScreenSetupStep.WIRELESS -> if (status.accessibilityEnabled) AppStrings.wizardWirelessAssist else AppStrings.wizardWirelessHelp
        ScreenSetupStep.PAIR -> if (status.accessibilityEnabled) AppStrings.wizardPairAssist else AppStrings.wizardPairHelp
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
        step == ScreenSetupStep.PAIR -> when {
            state.adbPairing -> AppStrings.screenOpenWireless
            status.accessibilityEnabled -> AppStrings.adbStartAutomaticPairing
            else -> AppStrings.adbStartPairing
        }
        else -> AppStrings.connectScreen
    }
    fun primary() {
        when {
            step == ScreenSetupStep.MODE -> execute {
                check(!state.active && !state.adbPairing) { AppStrings.screenModeBusy }
                if (!modeAvailable(selectedMode)) return@execute
                if (selectedMode != state.mode || !state.modeConfigured) {
                    controller.select(selectedMode)
                    connectionPort = ""
                    manualAdbSetup = false
                }
                val target = next(ScreenSetupStep.MODE)
                openWirelessOnArrival = target == ScreenSetupStep.WIRELESS && status.accessibilityEnabled
                go(target)
            }
            step == ScreenSetupStep.PORT -> execute { controller.setAdbPort(connectionPort); previous() }
            step == ScreenSetupStep.BRANDS || step == ScreenSetupStep.PAIR_HELP -> previous()
            step == ScreenSetupStep.DISCONNECT -> execute {
                controller.disconnect()
                history = arrayListOf(ScreenSetupStep.MODE.name, ScreenSetupStep.CONNECT.name)
            }
            step == ScreenSetupStep.COMPLETE -> if (ready) leave(onFinished) else go(next(ScreenSetupStep.MODE))
            complete -> go(next(ScreenSetupStep.MODE))
            step == ScreenSetupStep.NOTIFICATIONS -> notifications()
            step == ScreenSetupStep.ACCESSIBILITY -> open(ScreenSettingsTarget.ACCESSIBILITY)
            step == ScreenSetupStep.WIFI -> open(ScreenSettingsTarget.WIFI)
            step == ScreenSetupStep.DEVELOPER -> {
                openWirelessOnArrival = status.accessibilityEnabled
                open(ScreenSettingsTarget.ABOUT, assistNavigation = true)
            }
            step == ScreenSetupStep.WIRELESS -> open(ScreenSettingsTarget.WIRELESS, assistNavigation = true)
            step == ScreenSetupStep.PAIR -> pair()
            step == ScreenSetupStep.CONNECT -> connect()
        }
    }
    val enabled = !state.active && (step != ScreenSetupStep.MODE || !state.adbPairing) &&
        (step !in setOf(ScreenSetupStep.CONNECT, ScreenSetupStep.PORT, ScreenSetupStep.DISCONNECT) || !state.adbPairing) &&
        (step != ScreenSetupStep.MODE || modeAvailable(selectedMode)) &&
        (step != ScreenSetupStep.DISCONNECT || state.connected)
    val chrome = if (embedded) Modifier.fillMaxSize() else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
    val path = screenSetupPath(if (step == ScreenSetupStep.MODE) selectedMode else state.mode)
    val parentStep = history.asReversed().map(ScreenSetupStep::valueOf).first { !it.auxiliary }
    val stepNumber = path.indexOf(parentStep).coerceAtLeast(0) + 1
    val furthestStep = draft.furthestStep(state.mode)
    SideEffect { draft.recordStep(state.mode, stepNumber - 1) }
    val stepLabels = (if (embedded) setupAgentStepLabels() else emptyList()) + path.map(::screenStepLabel)
    fun completed(index: Int): Boolean {
        if (index < stepOffset) return isPrefixComplete(index)
        val target = path.getOrNull(index - stepOffset) ?: return false
        return index - stepOffset <= furthestStep &&
            (target == ScreenSetupStep.MODE || screenStepComplete(target, state, status, forcePair, developerConfirmed, wirelessConfirmed))
    }
    fun canReview(index: Int): Boolean {
        if (busy || state.active || state.adbPairing) return false
        if (index < stepOffset) return canSelectPrefix(index)
        return index - stepOffset != stepNumber - 1 && completed(index)
    }
    Box(chrome) {
        SetupWizardFrame(if (embedded) AppStrings.setupGuide else AppStrings.screenModeTitle,
            stepOffset + stepNumber, heading, description,
            back = ::previous, primaryText = primaryText, primary = ::primary, enabled = enabled, busy = busy,
            secondaryText = if (state.adbPairing && !step.auxiliary) AppStrings.adbCancelPairing else if (step == ScreenSetupStep.MODE && !embedded) AppStrings.back else AppStrings.wizardPrevious,
            error = error ?: state.error ?: if (state.active) AppStrings.screenModeBusy else null, steps = stepLabels,
            isStepComplete = ::completed, canSelectStep = ::canReview, selectStep = { index ->
                if (canReview(index)) {
                    if (index < stepOffset) leave { selectPrefix(index) } else go(path[index - stepOffset])
                }
            }) {
            when (step) {
                ScreenSetupStep.ACCESSIBILITY -> if (state.mode == ScreenMode.ADB && !complete) {
                    SettingsAction(AppStrings.wizardManualAdbSetup, enabled = enabled && !busy) {
                        manualAdbSetup = true
                        go(next(step))
                    }
                }
                ScreenSetupStep.MODE -> {
                    if (sdk >= 33) Text(AppStrings.adbScreenRecommendation, style = MaterialTheme.typography.bodyMedium)
                    val modes = if (sdk >= 33) listOf(ScreenMode.ADB, ScreenMode.CURRENT, ScreenMode.ROOT) else ScreenMode.entries
                    val canChoose = !busy && !state.active && !state.adbPairing
                    SettingsGroup {
                        modes.forEachIndexed { index, mode ->
                            if (index > 0) GroupDivider()
                            val available = modeAvailable(mode)
                            ChoiceRow(screenModeLabel(mode), available && selectedMode == mode, { selectedMode = mode },
                                enabled = canChoose && available)
                        }
                    }
                    if (sdk >= 33 && !rootGranted) {
                        if (rootCommandAvailable) {
                            TextButton(onClick = { execute {
                                rootGranted = controller.checkRootPermission()
                                if (!rootGranted) error = AppStrings.screenRootPermissionUnavailable
                            } }, enabled = canChoose) { Text(AppStrings.checkScreenRootPermission) }
                        } else SettingsCaption(AppStrings.screenRootPermissionUnavailable)
                    }
                    Text(when (selectedMode) { ScreenMode.CURRENT -> AppStrings.currentScreenModeHelp; ScreenMode.ADB -> AppStrings.virtualScreenModeHelp; ScreenMode.ROOT -> AppStrings.screenRootGuide },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedMode != ScreenMode.CURRENT && status.sdk < 33) Text(AppStrings.virtualScreenAndroidVersion, color = MaterialTheme.colorScheme.error)
                    if (embedded) TextButton(onClick = { leave(onFinished) }, enabled = !state.active && !state.adbPairing) { Text(AppStrings.wizardSkipScreen) }
                }
                ScreenSetupStep.DEVELOPER -> {
                    SettingsGroup(family.label()) {
                        Text(family.instructions(), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        GroupDivider()
                        SettingsItem(AppStrings.screenOtherBrands, { go(ScreenSetupStep.BRANDS) })
                    }
                    if (status.developerEnabled == null) SettingsGroup {
                        SettingsAction(AppStrings.wizardConfirmEnabled) { developerConfirmed = true; go(next(step)) }
                    }
                }
                ScreenSetupStep.WIRELESS -> if (status.wirelessEnabled == null) SettingsGroup {
                    SettingsAction(AppStrings.wizardConfirmEnabled) { wirelessConfirmed = true; go(next(step)) }
                }
                ScreenSetupStep.BRANDS -> SettingsGroup {
                    DeviceSettingsFamily.entries.forEachIndexed { index, candidate ->
                        if (index > 0) GroupDivider()
                        ChoiceRow(candidate.label(), candidate == family, { family = candidate })
                    }
                }
                ScreenSetupStep.PAIR -> SettingsGroup { SettingsItem(AppStrings.screenNoNotificationReply, { go(ScreenSetupStep.PAIR_HELP) }) }
                ScreenSetupStep.CONNECT -> {
                    SettingsCaption(if (state.connected) AppStrings.screenBackendReady else AppStrings.screenBackendDisconnected)
                    if (state.mode == ScreenMode.ADB) {
                        SettingsGroup {
                            SettingsItem(AppStrings.screenManualPort, { go(ScreenSetupStep.PORT) }, enabled = enabled && !busy)
                            if (error != null || state.error != null) {
                                GroupDivider()
                                SettingsItem(AppStrings.screenPairAgain, { forcePair = true; go(ScreenSetupStep.PAIR) }, enabled = enabled && !busy)
                            }
                        }
                    }
                }
                ScreenSetupStep.PORT -> SettingsGroup { SettingsField(connectionPort, { connectionPort = it }, AppStrings.adbConnectionPort,
                    enabled = enabled && !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                ScreenSetupStep.COMPLETE -> if (state.mode != ScreenMode.CURRENT && state.connected) SettingsGroup {
                    SettingsItem(AppStrings.disconnectScreen, { go(ScreenSetupStep.DISCONNECT) }, enabled = enabled)
                }
                else -> Unit
            }
        }
    }
}
