package com.github.ytlog.mobby.android.conversation.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import kotlinx.coroutines.*
import com.github.ytlog.mobby.android.conversation.ui.UiStrings as AppStrings

internal fun screenModeLabel(mode: ScreenMode): String = when (mode) {
    ScreenMode.CURRENT -> AppStrings.currentScreenMode
    ScreenMode.ADB -> AppStrings.adbScreenMode
    ScreenMode.SHIZUKU -> AppStrings.shizukuScreenMode
    ScreenMode.ROOT -> AppStrings.rootScreenMode
}

/** Installed by the application composition root; UI never acquires privileges directly. */
object ScreenUi { var controller: ScreenControlPort? = null }

class ScreenControlSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) MobbyDarkScheme else MobbyLightScheme) {
                Surface(Modifier.fillMaxSize()) {
                    ScreenUi.controller?.let { ScreenControlSettings(it, onBack = ::finish) }
                }
            }
        }
    }
}

@Composable internal fun ScreenControlSettings(controller: ScreenControlPort, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectionPort by remember(state.mode) { mutableStateOf("") }
    fun execute(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: AppStrings.screenBackendConnectionFailed }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PageHeader(AppStrings.screenModeTitle, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenMode.entries.forEach { mode ->
                Row {
                    RadioButton(selected = state.mode == mode, enabled = !busy && !state.active && !state.adbPairing, onClick = {
                        execute { controller.select(mode) }
                    })
                    TextButton(enabled = !busy && !state.active && !state.adbPairing, onClick = { execute { controller.select(mode) } }) { Text(screenModeLabel(mode)) }
                }
            }
            Text(if (state.mode == ScreenMode.CURRENT) AppStrings.currentScreenModeHelp else AppStrings.virtualScreenModeHelp)
            if (state.mode == ScreenMode.CURRENT) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(AppStrings.openAccessibilitySettings) }
            } else {
                Text(if (state.connected) AppStrings.screenBackendReady else AppStrings.screenBackendDisconnected)
                if (state.mode == ScreenMode.ADB) {
                    Text(AppStrings.adbScreenHelp)
                    OutlinedButton(enabled = !busy && !state.active, onClick = {
                        execute { if (state.adbPairing) controller.cancelAdbPairing() else controller.startAdbPairing() }
                    }) { Text(if (state.adbPairing) AppStrings.adbCancelPairing else AppStrings.adbStartPairing) }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, enabled = !busy && !state.active) {
                        Text(AppStrings.openWirelessDebugging)
                    }
                    if (state.adbPaired) Text(AppStrings.adbPaired)
                    OutlinedTextField(connectionPort, { connectionPort = it }, label = { Text(AppStrings.adbConnectionPort) },
                        enabled = !busy && !state.active && !state.connected && !state.adbPairing, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(AppStrings.adbConnectionPortHelp)
                }
                if (state.mode == ScreenMode.SHIZUKU) OutlinedButton(enabled = !busy && !state.active, onClick = {
                    execute { controller.authorizeShizuku() }
                }) { Text(AppStrings.authorizeShizuku) }
                Button(enabled = !busy && !state.active && !state.adbPairing, onClick = {
                    execute {
                        if (state.connected) controller.disconnect() else {
                            if (state.mode == ScreenMode.ADB) controller.setAdbPort(connectionPort)
                            controller.connect()
                        }
                    }
                }) { Text(if (state.connected) AppStrings.disconnectScreen else AppStrings.connectScreen) }
                if (android.os.Build.VERSION.SDK_INT < 33) Text(AppStrings.virtualScreenAndroidVersion)
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(AppStrings.openNotificationSettings) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
