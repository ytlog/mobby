package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.ScreenMode

import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlState
import android.content.ComponentName
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Settings choose a backend explicitly. A run holds its backend until close; failures never select another one. */
object ScreenControl {
    private val changes = MutableStateFlow(ScreenControlState())
    val state: StateFlow<ScreenControlState> = changes
    private var channel: ScreenChannel? = null
    private var release: (() -> Unit)? = null
    private var leases = 0
    private var connecting = false
    private var heartbeat: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val saved = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).getString("mode", null)
        changes.value = ScreenControlState(mode = ScreenMode.restore(saved), adbPaired = EmbeddedAdb.paired(context))
    }

    @Synchronized fun select(context: Context, mode: ScreenMode) {
        check(leases == 0 && !connecting && !state.value.adbPairing) { AppStrings.screenModeBusy }
        check(context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().putString("mode", mode.name).commit())
        disconnect()
        changes.value = ScreenControlState(mode, adbPaired = EmbeddedAdb.paired(context), adbPairingSuccess = state.value.adbPairingSuccess)
    }

    fun available(context: Context): Boolean = when (state.value.mode) {
        ScreenMode.CURRENT -> currentScreenPermission(context)
        else -> state.value.connected && channel?.alive == true
    }
    fun reason(context: Context): String? = when {
        available(context) -> null
        state.value.mode == ScreenMode.CURRENT -> AppStrings.enableMobbySScreenAccessibilityServiceInSystemSettings
        Build.VERSION.SDK_INT < 33 -> AppStrings.virtualScreenAndroidVersion
        else -> AppStrings.connectScreenBackend
    }
    private fun currentScreenPermission(context: Context): Boolean {
        if (android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val expected = ComponentName(context, ScreenAccessService::class.java)
        return android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    suspend fun connect(context: Context) = withContext(Dispatchers.IO) {
        val mode = synchronized(this@ScreenControl) {
            check(leases == 0 && !connecting && !state.value.adbPairing) { AppStrings.screenModeBusy }
            check(state.value.mode != ScreenMode.CURRENT)
            check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
            disconnect()
            connecting = true
            state.value.mode
        }
        try {
            val connected = when (mode) {
                ScreenMode.ADB -> EmbeddedAdb.connect(context)
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
            synchronized(this@ScreenControl) {
                connecting = false
                disconnect()
                changes.value = state.value.copy(error = when {
                    failure is kotlinx.coroutines.CancellationException -> null
                    mode == ScreenMode.ADB -> AppStrings.adbConnectionFailed
                    else -> AppStrings.screenBackendConnectionFailed
                })
            }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw IllegalStateException(if (mode == ScreenMode.ADB) AppStrings.adbConnectionFailed else AppStrings.screenBackendConnectionFailed, failure)
        }
    }

    internal suspend fun pairAdb(context: Context, port: String, code: String) {
        synchronized(this) {
            check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
            check(leases == 0 && !connecting && state.value.mode == ScreenMode.ADB) { AppStrings.screenModeBusy }
            disconnect(); connecting = true
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

    @Synchronized fun startAdbPairing(context: Context) {
        check(Build.VERSION.SDK_INT >= 33) { AppStrings.virtualScreenAndroidVersion }
        check(state.value.mode == ScreenMode.ADB && leases == 0 && !connecting && !state.value.adbPairing) { AppStrings.screenModeBusy }
        check(context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { AppStrings.adbPairingNotifications }
        disconnect()
        changes.value = state.value.copy(adbPairing = true, error = null)
        try { context.startForegroundService(android.content.Intent(context, AdbPairingService::class.java)) }
        catch (failure: Exception) { pairingFinished(AppStrings.adbPairingFailed); throw failure }
    }

    fun cancelAdbPairing(context: Context) { context.stopService(android.content.Intent(context, AdbPairingService::class.java)) }
    @Synchronized internal fun pairingFinished(error: String? = null) { changes.value = state.value.copy(adbPairing = false, error = error) }

    @Synchronized fun disconnect() {
        check(leases == 0 && !connecting) { AppStrings.screenModeBusy }
        heartbeat?.cancel(); heartbeat = null
        channel?.close(); channel = null
        release?.invoke(); release = null
        changes.value = ScreenControlState(mode = state.value.mode, adbPaired = state.value.adbPaired, adbPairing = state.value.adbPairing,
            adbPairingSuccess = state.value.adbPairingSuccess)
    }

    @Synchronized internal fun open(context: Context): ScreenSession {
        val mode = state.value.mode
        val session = selectScreenBackend(mode, {
            val stay = ScreenAccessService.stay()
            object : ScreenSession {
                private fun service() = ScreenAccessService.instance ?: error(AppStrings.accessibilityIsOffEnableMobbySScreenServiceIn)
                override fun operate(action: String, args: Map<String, String>, checkActive: () -> Unit) = service().operate(action, args, checkActive)
                override fun inspect(query: String?, expectedInput: String?) = service().inspect(query, expectedInput)
                override fun close() { stay.close() }
            }
        }, {
            requireUnlocked(context)
            check(leases == 0) { AppStrings.screenModeBusy }
            val connected = channel ?: error(AppStrings.connectScreenBackend)
            val opened = request(connected, "open")
            val id = opened.getInt("displayId")
            check(id > 0) { "Virtual screen must not be the main display" }
            val navigation = VirtualScreenNavigation(context, id)
            changes.value = state.value.copy(active = true, displayId = id, jpeg = null, error = null)
            object : ScreenSession {
                private fun onDisplay(action: String, args: Map<String, String> = emptyMap()) =
                    request(connected, action, args).also { check(it.getInt("displayId") == id) { "Virtual display changed" } }

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
                    ScreenCoordinator.chatAction {
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
                        changes.value = state.value.copy(jpeg = jpeg ?: state.value.jpeg, packageName = result.optString("packageName"), error = null)
                        ScreenAccessService.Observation(result.optString("text"), result.optString("packageName"),
                            result.getLong("observedAtEpochMillis"), ScreenScreenshot(jpeg, result.optString("screenshotStatus", "not_requested")), mode.name.lowercase(), id)
                    }
                override fun inspect(query: String?, expectedInput: String?): ScreenEvidence = ScreenCoordinator.chatAction {
                    requireUnlocked(context)
                    val args = buildMap { query?.let { put("query", it) }; expectedInput?.let { put("expectedInput", it) } }
                    val result = request(connected, "inspect", args)
                    check(result.getInt("displayId") == id)
                    ScreenEvidence(result.getString("packageName"), result.getString("shape"), result.getInt("matches"),
                        result.getInt("editable"), result.getBoolean("focusedEditable"), result.getBoolean("inputMatches"), result.getBoolean("passwordInput"))
                }
                override fun close() {
                    synchronized(connected) {
                        try { try { navigation.hide() } finally { request(connected, "close") } }
                        finally { changes.value = state.value.copy(active = false, displayId = null, jpeg = null, packageName = "") }
                    }
                }
            }
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

    /** Preview only reads the active virtual display; it never sends an input or changes the main display. */
    suspend fun refreshPreview() = withContext(Dispatchers.IO) {
        val connected = synchronized(this@ScreenControl) { channel.takeIf { state.value.active } } ?: return@withContext
        synchronized(connected) {
            if (!state.value.active) return@synchronized
            val result = request(connected, "frame")
            val bytes = result.optString("jpeg").takeIf(String::isNotEmpty)?.let { Base64.decode(it, Base64.NO_WRAP) }
            if (state.value.displayId == result.getInt("displayId")) changes.value = state.value.copy(jpeg = bytes)
        }
    }

    private fun requireUnlocked(context: Context) {
        if (!context.getSystemService(android.os.PowerManager::class.java).isInteractive ||
            context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            com.github.ytlog.mobby.android.runtime.api.device.deviceFailure(
                com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode.UNAVAILABLE, AppStrings.unlockForVirtualScreen)
    }

    private fun request(connection: ScreenChannel, action: String, args: Map<String, String> = emptyMap()): JSONObject {
        try {
            val result = connection.request(action, args)
            if (!result.optBoolean("ok") && result.optString("cause") == "TimeoutException")
                throw java.util.concurrent.TimeoutException("virtual_screen_timeout")
            if (!result.optBoolean("ok") && result.optString("cause") == "UnsupportedOperationException")
                com.github.ytlog.mobby.android.runtime.api.device.deviceFailure(
                    com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode.UNSUPPORTED_CAPABILITY)
            check(result.getBoolean("ok")) { result.optString("error", "virtual_screen_failed") }
            return result
        } catch (failure: Exception) {
            changes.value = state.value.copy(error = AppStrings.virtualScreenOperationFailed,
                connected = connection.alive)
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

internal fun screenShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** A persistent socket makes process death release the display. Requests are serialized and never retried. */
internal class ScreenChannel(endpoint: JSONObject, root: Boolean) : AutoCloseable {
    private val socket = Socket()
    private val input: DataInputStream
    private val output: DataOutputStream
    @Volatile var alive = true
        private set
    init {
        try {
            val port = endpoint.getInt("port"); require(port in 1..65535)
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000); socket.soTimeout = 20_000
            input = DataInputStream(socket.getInputStream()); output = DataOutputStream(socket.getOutputStream())
            send(JSONObject().put("token", endpoint.getString("token")))
            val hello = receive()
            check(hello.getBoolean("ok"))
            check(if (root) hello.getInt("uid") == 0 else hello.getInt("uid") in setOf(0, 2000))
        } catch (failure: Exception) { socket.close(); throw failure }
    }
    @Synchronized fun request(action: String, args: Map<String, String>): JSONObject {
        check(alive) { "virtual_screen_disconnected" }
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
}
