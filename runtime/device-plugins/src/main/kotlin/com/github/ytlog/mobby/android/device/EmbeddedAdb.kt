package com.github.ytlog.mobby.android.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionException
import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionFailure
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** App-owned pairing identity. No plaintext key on disk, downloaded executable, or Agent-facing ADB API. */
internal object EmbeddedAdb {
    private const val LAST_CONNECTION_PORT = "adb-last-connection-port"
    @Volatile var port: Int? = null
        private set

    fun parsePort(value: String): Int {
        require(value.matches(Regex("[0-9]{1,5}"))) { "invalid_adb_port" }
        return value.toInt().also { require(it in 1..65535) { "invalid_adb_port" } }
    }
    fun setPort(value: String) { port = if (value.isBlank()) null else parsePort(value.trim()) }
    fun validateCode(value: String) { require(value.matches(Regex("[0-9]{6}"))) { "invalid_pairing_code" } }
    fun paired(context: Context) = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).getBoolean("adb-paired", false)

    private fun forgetPairing(context: Context) {
        context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().remove("adb-paired").apply()
    }

    suspend fun pair(context: Context, value: String, code: String) = withContext(Dispatchers.IO) {
        val pairingPort = parsePort(value.trim()); validateCode(code)
        val identity = ScreenAdbIdentity.load(context)
        val pairing = PairingConnectionCtx("127.0.0.1", pairingPort, code.toByteArray(Charsets.UTF_8), identity.key, identity.certificate, "mobby-screen")
        val completed = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation { pairing.close() }
                Thread({
                    pairing.use {
                        try {
                            if (continuation.isActive) {
                                it.start()
                                continuation.resume(Unit)
                            }
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }, "screen-adb-pairing").apply { isDaemon = true; start() }
            }
            true
        }
        if (completed != true) throw java.util.concurrent.TimeoutException("adb_pairing_timeout")
        ensureActive()
        check(context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().putBoolean("adb-paired", true).commit())
    }

    /** ADB authenticates the app and locates the shell worker. The transport closes once attached. */
    suspend fun connect(context: Context, connectionPort: Int? = port, useRememberedPort: Boolean = true, discoveredPortHint: Int? = null): ScreenChannel {
        var created: ScreenChannel? = null
        try {
            return withContext(Dispatchers.IO) {
                val identity = ScreenAdbIdentity.load(context)
                val preferences = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE)
                val hint = preferences.getInt(LAST_CONNECTION_PORT, 0).takeIf {
                    connectionPort == null && useRememberedPort && it in 1..65535
                }
                val (selected, connection) = if (connectionPort != null) {
                    connectionPort to authenticateExplicit(context, connectionPort, identity)
                } else {
                    val candidates = flow {
                        (discoveredPortHint ?: hint)?.let { emit(it) }
                        emitAll(localPorts(context, pairing = false))
                    }
                    selectConnection(candidates) { authenticate(it, identity, 3) }
                }
                connection.use { adb ->
                    ensureActive()
                    val peerKey = checkNotNull(adb.tlsPeerPublicKey).encoded
                    adb.open(bootstrapCommand(context.applicationInfo.sourceDir)).use { stream ->
                        val line = readEndpointLine(stream.openInputStream()) { ensureActive() }
                        val endpoint = org.json.JSONObject(line)
                        check(endpoint.getInt("uid") == 2000) { "adb_worker_identity" }
                        ScreenChannel(endpoint, root = false, verifyAdbAuthorization = {
                            checkAuthorization(context) { verifyAuthorization(selected, identity, peerKey) }
                        }).also {
                            created = it
                            preferences.edit().putInt(LAST_CONNECTION_PORT, selected).apply()
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            created?.close()
            throw failure
        }
    }

    internal suspend fun <T : Any> selectConnection(candidates: Flow<Int>, probe: (Int) -> T): Pair<Int, T> {
        val attempted = mutableSetOf<Int>()
        var lastFailure: Exception? = null
        return candidates.mapNotNull { port ->
            if (!attempted.add(port)) return@mapNotNull null
            currentCoroutineContext().ensureActive()
            try {
                port to probe(port)
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is AdbPairingRequiredException &&
                    failure !is AdbAuthenticationFailedException) throw failure
                // A different local daemon can advertise the same service type. Its rejection
                // says nothing about the saved pairing with the intended daemon.
                lastFailure = failure
                null
            }
        }.firstOrNull() ?: throw AdbConnectionException(
            AdbConnectionFailure.ENDPOINT_NOT_FOUND, AppStrings.adbConnectionFailed, lastFailure,
        )
    }

    private fun authenticateExplicit(context: Context, port: Int, identity: ScreenAdbIdentity.Identity): AdbConnection =
        try {
            authenticate(port, identity, 15)
        } catch (failure: Exception) {
            if (failure !is AdbPairingRequiredException && failure !is AdbAuthenticationFailedException) throw failure
            forgetPairing(context)
            throw AdbConnectionException(AdbConnectionFailure.PAIRING_REQUIRED, AppStrings.adbConnectionPairingRequired, failure)
        }

    private fun authenticate(port: Int, identity: ScreenAdbIdentity.Identity, timeout: Long): AdbConnection {
        val adb = transport(port, identity)
        try {
            if (!adb.connect(timeout, TimeUnit.SECONDS, true)) throw SocketTimeoutException("adb_connection_timeout")
            return adb
        } catch (failure: Exception) {
            try { adb.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun transport(port: Int, identity: ScreenAdbIdentity.Identity): AdbConnection =
        AdbConnection.Builder("127.0.0.1", port).setApi(Build.VERSION.SDK_INT)
            .setPrivateKey(identity.key).setCertificate(identity.certificate).setDeviceName("mobby-screen").build()

    /**
     * Only a rejected identity or a different daemon revokes the pairing. An unreachable daemon, such as wireless
     * debugging turned off, throws its own failure and keeps the pairing so the user can reconnect without pairing again.
     */
    internal fun checkAuthorization(context: Context, verify: () -> Unit) {
        try {
            verify()
        } catch (cancelled: InterruptedException) {
            throw java.util.concurrent.CancellationException("ADB verification interrupted").apply { initCause(cancelled) }
        } catch (failure: Exception) {
            if (failure !is AdbAuthenticationFailedException && failure !is AdbPairingRequiredException &&
                failure !is AdbDaemonChangedException) throw failure
            forgetPairing(context)
            throw AdbAuthorizationRevoked(failure)
        }
    }

    private fun verifyAuthorization(port: Int, identity: ScreenAdbIdentity.Identity, peerKey: ByteArray) {
        transport(port, identity).use { probe ->
            check(probe.connect(3, TimeUnit.SECONDS, true)) { "adb_authorization_timeout" }
            val actualKey = checkNotNull(probe.tlsPeerPublicKey).encoded
            if (!MessageDigest.isEqual(peerKey, actualKey)) throw AdbDaemonChangedException()
        }
    }

    /** Bulk reads consume an ADB packet before its following CLSE can mark the stream closed. */
    internal fun readEndpointLine(input: java.io.InputStream, checkActive: () -> Unit = {}): String {
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(1025)
        while (true) {
            checkActive()
            val count = input.read(chunk)
            check(count > 0) { "adb_worker_start_failed" }
            val newline = (0 until count).firstOrNull { chunk[it] == '\n'.code.toByte() }
            val length = newline ?: count
            check(bytes.size() + length <= 1024) { "adb_worker_endpoint_size" }
            bytes.write(chunk, 0, length)
            if (newline != null) return bytes.toString("UTF-8")
        }
    }

    internal fun bootstrapCommand(apk: String): String {
        require(apk.startsWith("/") && !apk.contains('\n') && !apk.contains('\u0000'))
        return "shell:CLASSPATH=${screenShellQuote(apk)} /system/bin/app_process /system/bin com.github.ytlog.mobby.android.device.privileged.AdbScreenMain"
    }

    private fun isLocal(address: InetAddress): Boolean = address.isLoopbackAddress ||
        NetworkInterface.getNetworkInterfaces().toList().any { network -> network.inetAddresses.toList().contains(address) }

    internal suspend fun discoverPairingPort(context: Context): Int =
        localPorts(context, pairing = true).firstOrNull()
            ?: throw java.util.concurrent.TimeoutException("adb_discovery_timeout")

    @Suppress("DEPRECATION")
    private fun localPorts(context: Context, pairing: Boolean): Flow<Int> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val queue = java.util.ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val stopped = java.util.concurrent.atomic.AtomicBoolean()
        fun resolveNext() {
            if (stopped.get() || resolving || queue.isEmpty()) return
            resolving = true
            nsd.resolveService(queue.removeFirst(), object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolving = false; resolveNext() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    if (info.host?.let(::isLocal) == true && info.port in 1..65535) {
                        trySend(info.port)
                    }
                    resolveNext()
                }
            })
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (stopped.get()) return
                queue.add(info)
                resolveNext()
            }
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        nsd.discoverServices(if (pairing) "_adb-tls-pairing._tcp." else "_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        val deadline = launch {
            delay(if (pairing) 90_000 else 10_000)
            close()
        }
        awaitClose {
            stopped.set(true)
            deadline.cancel()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}

/** The device no longer accepts this app's ADB identity. The saved pairing is cleared. */
internal class AdbAuthorizationRevoked(cause: Throwable? = null) : Exception("adb_authorization_revoked", cause)

/** The daemon answering the saved port is not the one that started the screen worker. */
internal class AdbDaemonChangedException : Exception("adb_daemon_changed")
