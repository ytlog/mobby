package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionException
import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionFailure
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.EffectState
import com.github.ytlog.mobby.android.runtime.api.device.ManualScreenInput
import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure

import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import android.content.Context
import android.os.Build
import android.util.Base64
import com.github.ytlog.mobby.android.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Settings choose a backend explicitly. A run holds its backend until close; failures never select another one. */
object ScreenControl {
    private val changes = MutableStateFlow(ScreenControlState())
    val state: StateFlow<ScreenControlState> = changes
    @Volatile private var channel: ScreenChannel? = null
    private var release: (() -> Unit)? = null
    @Volatile private var leases = 0
    private var connecting = false
    private var heartbeat: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val saved = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).getString("mode", null)
        changes.value = ScreenControlState(mode = ScreenMode.restore(saved), adbPaired = EmbeddedAdb.paired(context), modeConfigured = saved != null)
    }

    suspend fun hasRootCommand(): Boolean = withContext(Dispatchers.IO) {
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { directory ->
            directory.startsWith('/') && File(directory, "su").canExecute()
        }
    }

    /** Run only when the user checks permission. The worker still verifies its own UID at connection. */
    suspend fun checkRootPermission(): Boolean = runInterruptible(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", "/system/bin/id -u").start()
            try {
                process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 &&
                    process.inputStream.bufferedReader().use { it.readLine() } == "0"
            } finally {
                process.destroyForcibly()
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @Synchronized fun select(context: Context, mode: ScreenMode) {
        check(leases == 0 && !connecting && !state.value.adbPairing) { AppStrings.screenModeBusy }
        check(context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().putString("mode", mode.name).commit())
        disconnect()
        changes.value = ScreenControlState(mode, adbPaired = EmbeddedAdb.paired(context), adbPairingSuccess = state.value.adbPairingSuccess, modeConfigured = true)
    }

    fun available(context: Context): Boolean = when (state.value.mode) {
        // Admission follows the user's grant in settings; a service rebind must not reject a whole turn.
        ScreenMode.CURRENT -> ScreenAccessService.status(context) != ScreenAccessService.Status.OFF
        ScreenMode.ADB -> leases > 0 || (state.value.connected && channel?.alive == true)
        ScreenMode.ROOT -> state.value.connected && channel?.alive == true
    }
    fun reason(context: Context): String? = when {
        available(context) -> null
        state.value.mode == ScreenMode.CURRENT -> AppStrings.enableMobbySScreenAccessibilityServiceInSystemSettings
        Build.VERSION.SDK_INT < 33 -> AppStrings.virtualScreenAndroidVersion
        state.value.mode == ScreenMode.ADB && channel?.authorizationLost == true -> AppStrings.adbScreenAuthorizationRequired
        else -> AppStrings.connectScreenBackend
    }

    private fun canReconnectAdb() =
        state.value.mode == ScreenMode.ADB && channel?.alive != true

    internal fun workflowPaused(pause: com.github.ytlog.mobby.android.runtime.api.device.ScreenWorkflowPause?) {
        changes.update { it.copy(workflowPause = pause) }
    }

    suspend fun connect(context: Context) = connect(context, null, null)

    internal suspend fun restoreSavedAdb(context: Context) {
        if (android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1) == 0) return
        try {
            connect(context, null, null, restoreSavedPairing = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // connect publishes the failure. Authorization rechecks the backend and can open the setup wizard.
        }
    }

    internal suspend fun connectAfterPairing(context: Context, pairingSuccess: Long, connectionPort: Int?) =
        connect(context, pairingSuccess, connectionPort)

    private suspend fun connect(context: Context, pairingSuccess: Long?, connectionPort: Int?, restoreSavedPairing: Boolean = false) = withContext(Dispatchers.IO) {
        val mode = synchronized(this@ScreenControl) {
            val current = state.value
            if (restoreSavedPairing && (current.mode != ScreenMode.ADB || !current.adbPaired || current.connected ||
                    current.adbPairing || leases > 0 || connecting || Build.VERSION.SDK_INT < 33)) return@withContext
            if (pairingSuccess != null) {
                check(current.mode == ScreenMode.ADB && current.adbPairing &&
                    current.adbPairingSuccess == pairingSuccess) { AppStrings.screenModeBusy }
            }
            check((leases == 0 || canReconnectAdb()) && !connecting &&
                (!state.value.adbPairing || pairingSuccess != null)) { AppStrings.screenModeBusy }
            check(state.value.mode != ScreenMode.CURRENT)
            check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
            clearConnection()
            connecting = true
            state.value.mode
        }
        try {
            val connected = when (mode) {
                ScreenMode.ADB -> if (pairingSuccess == null) EmbeddedAdb.connect(context)
                    else EmbeddedAdb.connect(context, connectionPort, useRememberedPort = false)
                ScreenMode.ROOT -> rootChannel(context)
                ScreenMode.CURRENT -> error("current_screen_has_no_backend")
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                connected.close()
                throw kotlinx.coroutines.CancellationException("screen_connection_cancelled")
            }
            synchronized(this@ScreenControl) {
                channel = connected
                changes.value = state.value.copy(connected = true, error = null)
                heartbeat = scope.launch {
                    while (isActive) {
                        delay(30_000)
                        try { request(connected, "ping") } catch (_: Exception) { break }
                    }
                }
                connecting = false
            }
        } catch (failure: Exception) {
            val message = when {
                failure is kotlinx.coroutines.CancellationException -> null
                failure is AdbConnectionException -> failure.message
                mode == ScreenMode.ADB -> AppStrings.adbConnectionFailed
                else -> AppStrings.screenBackendConnectionFailed
            }
            synchronized(this@ScreenControl) {
                connecting = false
                clearConnection()
                changes.value = state.value.copy(error = message,
                    adbPaired = state.value.adbPaired &&
                        (failure as? AdbConnectionException)?.reason != AdbConnectionFailure.PAIRING_REQUIRED)
            }
            if (failure is kotlinx.coroutines.CancellationException || failure is AdbConnectionException) throw failure
            throw IllegalStateException(message, failure)
        }
    }

    internal suspend fun pairAdb(context: Context, port: String, code: String) {
        synchronized(this) {
            check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
            check((leases == 0 || canReconnectAdb()) && !connecting && state.value.mode == ScreenMode.ADB) { AppStrings.screenModeBusy }
            clearConnection(); connecting = true
        }
        try {
            EmbeddedAdb.pair(context, port, code)
            changes.value = state.value.copy(adbPaired = true, error = null, adbPairingSuccess = state.value.adbPairingSuccess + 1)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // Diagnostic types only: never log the pairing code, keys, addresses or exception messages.
            android.util.Log.w("ScreenAdb", generateSequence<Throwable>(failure) { it.cause }.take(8).joinToString(" -> ") { it.javaClass.name })
            throw IllegalStateException(AppStrings.adbPairingFailed, failure)
        } finally { synchronized(this) { connecting = false } }
    }

    @Synchronized fun setAdbPort(context: Context, value: String) {
        check(leases == 0 && !connecting && state.value.mode == ScreenMode.ADB) { AppStrings.screenModeBusy }
        try { EmbeddedAdb.setPort(value) } catch (_: IllegalArgumentException) { error(AppStrings.adbInvalidPort) }
    }

    @Synchronized fun startAdbPairing(context: Context, returnToActivity: String? = null) {
        check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
        check(state.value.mode == ScreenMode.ADB && (leases == 0 || canReconnectAdb()) && !connecting && !state.value.adbPairing) { AppStrings.screenModeBusy }
        check(context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { AppStrings.adbPairingNotifications }
        clearConnection()
        changes.value = state.value.copy(adbPairing = true, error = null)
        val intent = android.content.Intent(context, AdbPairingService::class.java)
            .putExtra(AdbPairingService.RETURN_TO_ACTIVITY, returnToActivity)
        try { context.startForegroundService(intent) }
        catch (failure: Exception) { pairingFinished(AppStrings.adbPairingFailed); throw failure }
    }

    fun cancelAdbPairing(context: Context) { context.stopService(android.content.Intent(context, AdbPairingService::class.java)) }
    @Synchronized internal fun pairingFinished(error: String? = null) { changes.value = state.value.copy(adbPairing = false, error = error) }

    @Synchronized fun disconnect() {
        check(leases == 0 && !connecting) { AppStrings.screenModeBusy }
        clearConnection()
    }

    private fun clearConnection() {
        heartbeat?.cancel(); heartbeat = null
        channel?.close(); channel = null
        release?.invoke(); release = null
        changes.value = ScreenControlState(mode = state.value.mode, adbPaired = state.value.adbPaired, adbPairing = state.value.adbPairing,
            adbPairingSuccess = state.value.adbPairingSuccess, modeConfigured = state.value.modeConfigured, workflowPause = state.value.workflowPause)
    }

    @Synchronized internal fun open(context: Context): ScreenSession {
        val mode = state.value.mode
        val session = selectScreenBackend(mode, {
            val stay = ScreenAccessService.require(context).stay.acquire()
            object : ScreenSession {
                private fun service() = ScreenAccessService.require(context)
                override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit) = service().operate(action, args, checkActive)
                override fun inspect(query: String?, expectedInput: String?) = service().inspect(query, expectedInput)
                override fun close() { stay.close() }
            }
        }, {
            requireUnlocked(context)
            check(leases == 0) { AppStrings.screenModeBusy }
            val connected = channel ?: deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.connectScreenBackend)
            val initial = openVirtual(context, mode, connected)
            if (mode == ScreenMode.ADB) RecoveringAdbScreenSession(
                initial, { channel?.alive == true && state.value.active }, { channel?.alive == true },
                checkAuthorization = { channel?.requireAuthorization() },
            ) {
                synchronized(this) {
                    check(state.value.mode == ScreenMode.ADB)
                    requireUnlocked(context)
                    val replacement = checkNotNull(channel).also { check(it.alive) }
                    openVirtual(context, mode, replacement)
                }
            } else initial
        })
        leases++
        val closed = AtomicBoolean()
        return object : ScreenSession by session {
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try { session.close() } finally { synchronized(this@ScreenControl) { leases-- } }
            }
        }
    }

    private fun openVirtual(context: Context, mode: ScreenMode, connected: ScreenChannel): ScreenSession {
        val opened = request(connected, "open")
        val id = opened.getInt("displayId")
        val navigation = VirtualScreenNavigation(context, id)
        changes.value = state.value.copy(active = true, displayId = id, jpeg = null, packageName = "", error = null,
            manualControlId = null, readRequiredAfterHandoff = false)
        return object : ScreenSession {
            private fun onDisplay(action: String, args: Map<String, String> = emptyMap()) =
                request(connected, action, args, expectedDisplay = id)

            private fun openNavigation(home: Boolean): JSONObject {
                val entries = if (home) onDisplay("apps").getString("text").lineSequence()
                    .filter { it.isNotBlank() && it != context.packageName }
                    .map { VirtualScreenNavigation.Entry(it, "launch", mapOf("package" to it)) }.toList()
                else onDisplay("tasks").getJSONArray("tasks").let { tasks ->
                    (0 until tasks.length()).map { index ->
                        val task = tasks.getJSONObject(index)
                        val taskId = task.getInt("id").toString()
                        VirtualScreenNavigation.Entry("${task.getString("package")} (task $taskId)", "focus_task", mapOf("id" to taskId))
                    }
                }
                navigation.show(if (home) "Virtual apps" else "Virtual tasks", entries)
                return onDisplay("navigation_snapshot")
            }

            override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit): ScreenAccessService.Observation =
                agentAction(id, action, checkActive) {
                    checkActive()
                    requireUnlocked(context)
                    var result = when {
                        action == "home" || action == "recents" -> openNavigation(action == "home")
                        action == "back" && navigation.visible -> { navigation.hide(); onDisplay("observe") }
                        action == "launch" -> { navigation.hide(); onDisplay(action, args) }
                        else -> onDisplay(action, args)
                    }
                    if (navigation.visible && action in setOf("click", "tap", "swipe")) {
                        navigation.takeSelection()?.let { entry ->
                            checkActive()
                            requireUnlocked(context)
                            navigation.hide()
                            result = onDisplay(entry.action, entry.args)
                        }
                    }
                    checkActive()
                    val jpeg = result.optString("jpeg").takeIf(String::isNotEmpty)?.let { Base64.decode(it, Base64.NO_WRAP) }
                    val observedPackage = result.optString("packageName")
                    changes.update { current ->
                        if (current.active && current.displayId == id)
                            current.copy(jpeg = jpeg ?: current.jpeg, packageName = observedPackage.ifBlank { current.packageName }, error = null)
                        else current
                    }
                    ScreenAccessService.Observation(result.optString("text"), observedPackage,
                        result.getLong("observedAtEpochMillis"), ScreenScreenshot(jpeg, result.optString("screenshotStatus", "not_requested")), mode.name.lowercase(), id)
                }
            override fun inspect(query: String?, expectedInput: String?): ScreenEvidence = ScreenCoordinator.chatAction {
                requireAgentAccess(id, "inspect")
                requireUnlocked(context)
                val args = buildMap { query?.let { put("query", it) }; expectedInput?.let { put("expectedInput", it) } }
                val result = onDisplay("inspect", args)
                ScreenEvidence(result.getString("packageName"), result.getString("shape"), result.getInt("matches"),
                    result.getInt("editable"), result.getBoolean("focusedEditable"), result.getBoolean("inputMatches"),
                    result.getBoolean("passwordInput"), result.getString("scene"))
            }
            override fun close() {
                synchronized(connected) {
                    try { try { navigation.hide() } finally { if (connected.alive) request(connected, "close") } }
                    finally {
                        changes.update { current ->
                            if (current.displayId == id && channel === connected)
                                current.copy(active = false, displayId = null, jpeg = null, packageName = "",
                                    manualControlId = null, readRequiredAfterHandoff = false)
                            else current
                        }
                    }
                }
            }
        }
    }

    private fun requireAgentAccess(display: Int, action: String) {
        val current = state.value
        if (!current.active || current.displayId != display) {
            channel?.requireAuthorization()
            deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenDisconnected)
        }
        current.requireAgentScreenAccess(action)
    }

    private fun <T : Any> agentAction(display: Int, action: String, checkActive: () -> Unit, block: () -> T): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (true) {
            checkActive()
            val result = if (state.value.manualControlId != null) null else ScreenCoordinator.chatAction {
                checkActive()
                val current = state.value
                if (current.displayId == display && current.manualControlId != null) return@chatAction null
                requireAgentAccess(display, action)
                block().also {
                    if (action == "snapshot") changes.update { latest ->
                        if (latest.displayId == display) latest.copy(readRequiredAfterHandoff = false) else latest
                    }
                }
            }
            if (result != null) return result
            if (System.nanoTime() >= deadline) deviceFailure(DeviceErrorCode.TIMEOUT, AppStrings.virtualScreenInUserControl)
            Thread.sleep(100)
        }
    }

    private fun activeDisplay(): Pair<ScreenChannel, Int> {
        val current = state.value
        val display = current.displayId
        val connection = channel
        connection?.requireAuthorization()
        if (!current.active || display == null || display <= 0 || connection?.alive != true)
            deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenDisconnected)
        return connection to display
    }

    fun takeOver(context: Context): String = takeOver(context, null)

    private fun takeOver(context: Context, expected: Pair<ScreenChannel, Int>?): String = ScreenCoordinator.chatAction {
        val (connection, display) = activeDisplay()
        if (expected != null && expected != (connection to display)) deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenDisconnected)
        requireUnlocked(context)
        state.value.manualControlId?.let { return@chatAction it }
        request(connection, "manual_begin", expectedDisplay = display)
        val id = UUID.randomUUID().toString()
        changes.update {
            if (it.active && it.displayId == display && channel === connection) it.copy(manualControlId = id, error = null) else it
        }
        check(state.value.manualControlId == id) { AppStrings.virtualScreenDisconnected }
        id
    }

    private fun manualDisplay(context: Context, id: String, requireInteractive: Boolean = true): Pair<ScreenChannel, Int> {
        val (connection, display) = activeDisplay()
        check(state.value.manualControlId == id) { AppStrings.virtualScreenDisconnected }
        if (requireInteractive) requireUnlocked(context)
        return connection to display
    }

    fun prepareManualText(context: Context, id: String): String = ScreenCoordinator.chatAction {
        val (connection, display) = manualDisplay(context, id)
        request(connection, "manual_prepare_text", expectedDisplay = display).getString("label")
    }

    fun manualInput(context: Context, id: String, input: ManualScreenInput) = ScreenCoordinator.chatAction {
        val (connection, display) = manualDisplay(context, id, requireInteractive = input != ManualScreenInput.CancelTouch)
        when (input) {
            is ManualScreenInput.Touch -> {
                require(input.x in 0f..1f && input.y in 0f..1f)
                request(connection, "manual_touch", mapOf("phase" to input.phase.name, "x" to input.x.toString(),
                    "y" to input.y.toString()), expectedDisplay = display)
            }
            ManualScreenInput.CancelTouch -> request(connection, "manual_cancel_touch", expectedDisplay = display)
            is ManualScreenInput.Text -> {
                require(input.value.isNotEmpty() && input.value.length <= 2000 && '\u0000' !in input.value)
                request(connection, "private_input", mapOf("values" to JSONArray(listOf(input.value)).toString()), expectedDisplay = display)
            }
            ManualScreenInput.Back -> request(connection, "back", expectedDisplay = display)
        }
        Unit
    }

    fun handBack(context: Context, id: String) = ScreenCoordinator.chatAction {
        val (connection, display) = manualDisplay(context, id)
        request(connection, "manual_end", expectedDisplay = display)
        changes.update {
            if (it.manualControlId == id && channel === connection)
                it.copy(manualControlId = null, readRequiredAfterHandoff = true, jpeg = null, error = null)
            else it
        }
    }

    internal fun requestUserInput(context: Context, label: String?, handoffOnly: Boolean, execution: DeviceExecution,
        checkActive: () -> Unit): Boolean {
        val (connection, display) = activeDisplay()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(125)
        val labels = agentAction(display, if (handoffOnly) "request_handoff" else "request_input", checkActive) {
            requireUnlocked(context)
            if (handoffOnly) emptyList() else {
                val fields = request(connection, "prepare_private_input",
                    mapOf("focusedOnly" to (label != null).toString()), expectedDisplay = display).getJSONArray("labels")
                if (label != null) listOf(label) else List(fields.length(), fields::getString)
            }
        }
        try {
            return when (val answer = PrivateScreenInput.await(execution, labels, checkActive)) {
                PrivateScreenInput.Answer.TakeOver -> {
                    checkActive()
                    val id = takeOver(context, connection to display)
                    execution.waiting("screen.user_control", id, allowCancel = false)
                    while (state.value.manualControlId == id) {
                        checkActive()
                        if (System.nanoTime() >= deadline) deviceFailure(DeviceErrorCode.TIMEOUT, AppStrings.virtualScreenInUserControl)
                        Thread.sleep(100)
                    }
                    checkActive()
                    if (activeDisplay() != (connection to display)) deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenDisconnected)
                    true
                }
                is PrivateScreenInput.Answer.Value -> agentAction(display, "request_input", checkActive) {
                    requireUnlocked(context)
                    execution.running("screen.private_input")
                    request(connection, "private_input", mapOf("values" to JSONArray(answer.fields).toString()), expectedDisplay = display)
                    false
                }
                PrivateScreenInput.Answer.Cancel -> deviceFailure(DeviceErrorCode.CANCELLED)
            }
        } finally {
            if (!handoffOnly && state.value.manualControlId == null) ScreenCoordinator.chatAction {
                if (state.value.manualControlId == null && state.value.displayId == display && channel === connection)
                    clearPrivateInput(connection, display)
            }
        }
    }

    private fun clearPrivateInput(connection: ScreenChannel, display: Int) {
        if (connection.alive) runCatching { request(connection, "cancel_private_input", expectedDisplay = display) }
    }

    /** Preview only reads the active virtual display; it never sends an input or changes the main display. */
    suspend fun refreshPreview() = withContext(Dispatchers.IO) {
        val connected = synchronized(this@ScreenControl) { channel.takeIf { state.value.active } } ?: return@withContext
        synchronized(connected) {
            if (!state.value.active) return@synchronized
            val id = state.value.displayId ?: return@synchronized
            val result = request(connected, "frame", mapOf("after" to connected.previewFrameAt.toString()), expectedDisplay = id)
            if (result.optString("screenshotStatus") == "unchanged") return@synchronized
            val bytes = result.optString("jpeg").takeIf(String::isNotEmpty)?.let { Base64.decode(it, Base64.NO_WRAP) }
            connected.previewFrameAt = result.optLong("frameAt")
            changes.update { current ->
                if (current.active && current.displayId == id) current.copy(jpeg = bytes) else current
            }
        }
    }

    private fun requireUnlocked(context: Context) {
        if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive ||
            context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            com.github.ytlog.mobby.android.runtime.api.device.deviceFailure(
                com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode.UNAVAILABLE, AppStrings.unlockForVirtualScreen)
    }

    internal fun request(connection: ScreenChannel, action: String, args: Map<String, String> = emptyMap(),
        expectedDisplay: Int? = null): JSONObject {
        try {
            val result = connection.request(action, args)
            if (!result.getBoolean("ok")) {
                when (result.optString("detail")) {
                    "invalid_display", "virtual_screen_closed", "virtual_screen_not_open" -> {
                        connection.close()
                        deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenDisconnected)
                    }
                    "no_virtual_window", "no_virtual_app_window" ->
                        deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenWindowUnavailable)
                    "control_not_found" -> deviceFailure(DeviceErrorCode.NOT_FOUND, AppStrings.virtualScreenControlNotFound)
                    "control_not_unique" -> deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, AppStrings.virtualScreenControlNotUnique)
                    "no_editable_field" -> deviceFailure(DeviceErrorCode.NOT_FOUND, AppStrings.virtualScreenNoEditableField)
                    "manual_input_target_missing" -> deviceFailure(DeviceErrorCode.NOT_FOUND, AppStrings.virtualScreenManualSelectField)
                    "private_input_fields_changed" -> deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenManualFieldChanged, EffectState.UNKNOWN)
                    "click_refused" -> deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenClickRefused, EffectState.UNKNOWN)
                    "app_already_on_other_display" ->
                        deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY, AppStrings.virtualScreenAppOnOtherDisplay)
                    "app_not_on_virtual_display" ->
                        deviceFailure(DeviceErrorCode.RESULT_UNCONFIRMED, AppStrings.virtualScreenAppNotVisible, EffectState.UNKNOWN)
                }
                when (result.optString("cause")) {
                    "TimeoutException" -> throw java.util.concurrent.TimeoutException("virtual_screen_timeout")
                    "UnsupportedOperationException" -> deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
                }
                error(result.optString("error", "virtual_screen_failed"))
            }
            val display = result.optInt("displayId", -1)
            if ((action == "open" && display <= 0) || (expectedDisplay != null && display != expectedDisplay)) {
                connection.close()
                error("virtual_display_identity")
            }
            return result
        } catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            if (failure is DeviceFailure && failure.error.code == DeviceErrorCode.ADB_AUTHORIZATION_REQUIRED) {
                changes.update { current ->
                    if (channel !== connection) current else current.copy(connected = false, active = false,
                        displayId = null, jpeg = null, packageName = "", adbPaired = false, manualControlId = null,
                        readRequiredAfterHandoff = false, error = AppStrings.adbScreenAuthorizationRequired)
                }
                throw failure
            }
            // A failed lifecycle operation leaves ownership uncertain. Closing the socket
            // makes the worker release its display, even if the response was lost.
            if (action in setOf("open", "close", "ping") || failure is java.util.concurrent.TimeoutException) connection.close()
            changes.update { current ->
                when {
                    channel !== connection -> current
                    connection.alive -> current.copy(error = (failure as? DeviceFailure)?.error?.message
                        ?.takeIf(String::isNotBlank) ?: AppStrings.virtualScreenOperationFailed)
                    else -> current.copy(connected = false, active = false, displayId = null, jpeg = null,
                        packageName = "", manualControlId = null, readRequiredAfterHandoff = false,
                        error = (failure as? DeviceFailure)?.error?.message?.takeIf(String::isNotBlank)
                            ?: if (current.mode == ScreenMode.ADB && leases > 0)
                            AppStrings.adbScreenWaitingForReconnect else AppStrings.virtualScreenDisconnected)
                }
            }
            throw failure
        }
    }

    private fun rootChannel(context: Context): ScreenChannel {
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH=${screenShellQuote(apk)} /system/bin/app_process /system/bin com.github.ytlog.mobby.android.device.privileged.RootScreenMain"
        val process = ProcessBuilder("su", "-c", command).start()
        // Drain diagnostics without logging command arguments, tokens or device contents.
        Thread { process.errorStream.use { stream -> val buffer = ByteArray(1024); while (stream.read(buffer) >= 0) Unit } }.apply { isDaemon = true; start() }
        release = { process.destroy() }
        val endpoint = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            .get(30, TimeUnit.SECONDS) ?: error("root_start_failed")
        return ScreenChannel(JSONObject(endpoint), root = true)
    }
}

internal fun ScreenControlState.requireAgentScreenAccess(action: String) {
    if (manualControlId != null) deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenInUserControl)
    if (readRequiredAfterHandoff && action !in setOf("snapshot", "apps"))
        deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.virtualScreenReadAfterHandoff)
}

internal fun screenShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** A persistent socket makes process death release the display. Requests are serialized and never retried. */
internal class ScreenChannel(
    endpoint: JSONObject,
    root: Boolean,
    private val verifyAdbAuthorization: (() -> Unit)? = null,
) : AutoCloseable {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: DataOutputStream
    // Accessed while holding the channel monitor during preview polling.
    var previewFrameAt = 0L
    @Volatile var alive = true
        private set
    @Volatile var authorizationLost = false
        private set

    fun requireAuthorization() {
        if (authorizationLost) deviceFailure(DeviceErrorCode.ADB_AUTHORIZATION_REQUIRED, AppStrings.adbScreenAuthorizationRequired)
    }

    init {
        try {
            val port = endpoint.getInt("port"); require(port in 1..65535)
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000); socket.soTimeout = 20_000; socket.tcpNoDelay = true
            input = DataInputStream(socket.getInputStream()); output = DataOutputStream(socket.getOutputStream())
            send(JSONObject().put("token", endpoint.getString("token")))
            val hello = receive()
            check(hello.getBoolean("ok"))
            check(hello.getInt("uid") == if (root) 0 else 2000) { "screen_worker_identity" }
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    @Synchronized fun request(action: String, args: Map<String, String>): JSONObject {
        requireAuthorization()
        check(alive) { "virtual_screen_disconnected" }
        // Maintenance reads and cleanup cannot dispatch Agent actions.
        if (verifyAdbAuthorization != null && action !in MAINTENANCE_ACTIONS) {
            try { verifyAdbAuthorization() }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: AdbAuthorizationRevoked) {
                authorizationLost = true
                close()
                requireAuthorization()
            } catch (_: Exception) {
                // Authorization cannot be confirmed, so nothing is sent. The pairing itself was not rejected.
                close()
                deviceFailure(DeviceErrorCode.UNAVAILABLE, AppStrings.adbWirelessDebuggingUnreachable)
            }
        }
        try {
            send(JSONObject().put("action", action).put("args", JSONObject(args)))
            return receive()
        } catch (failure: Exception) {
            close()
            if (failure is java.net.SocketTimeoutException)
                throw java.util.concurrent.TimeoutException("virtual_screen_timeout").apply { initCause(failure) }
            throw failure
        }
    }
    private fun send(value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 65_536)
        output.writeInt(bytes.size); output.write(bytes); output.flush()
    }
    private fun receive(): JSONObject {
        val size = input.readInt(); require(size in 1..4 * 1024 * 1024)
        val bytes = ByteArray(size); input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    override fun close() { alive = false; socket.close() }

    private companion object {
        val MAINTENANCE_ACTIONS = setOf("frame", "close", "cancel_private_input", "manual_cancel_touch")
    }
}
