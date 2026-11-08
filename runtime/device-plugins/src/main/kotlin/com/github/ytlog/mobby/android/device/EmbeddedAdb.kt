package com.github.ytlog.mobby.android.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** App-owned pairing identity. No plaintext key on disk, downloaded executable, or Agent-facing ADB API. */
internal object EmbeddedAdb {
    @Volatile var port: Int? = null
        private set

    fun parsePort(value: String): Int {
        require(value.matches(Regex("[0-9]{1,5}"))) { "invalid_adb_port" }
        return value.toInt().also { require(it in 1..65535) { "invalid_adb_port" } }
    }
    fun setPort(value: String) { port = if (value.isBlank()) null else parsePort(value.trim()) }
    fun validateCode(value: String) { require(value.matches(Regex("[0-9]{6}"))) { "invalid_pairing_code" } }
    fun paired(context: Context) = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).getBoolean("adb-paired", false)

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

    /** A single fixed bootstrap command. The ADB connection is closed before returning the screen socket. */
    suspend fun connect(context: Context, connectionPort: Int? = port): ScreenChannel {
        var created: ScreenChannel? = null
        try {
            val selected = connectionPort ?: discover(context)
            return withContext(Dispatchers.IO) {
                val identity = ScreenAdbIdentity.load(context)
                transport(selected, identity).use { adb ->
                    ensureActive()
                    check(adb.connect(15, TimeUnit.SECONDS, true)) { "adb_connection_timeout" }
                    adb.open(bootstrapCommand(context.applicationInfo.sourceDir)).use { stream ->
                        val line = readEndpointLine(stream.openInputStream()) { ensureActive() }
                        val endpoint = org.json.JSONObject(line)
                        check(endpoint.getInt("uid") == 2000) { "adb_worker_identity" }
                        val peerKey = checkNotNull(adb.tlsPeerPublicKey).encoded
                        ScreenChannel(endpoint, root = false, verifyAdbAuthorization = {
                            try {
                                verifyAuthorization(selected, identity, peerKey)
                            } catch (cancelled: InterruptedException) {
                                throw java.util.concurrent.CancellationException("ADB verification interrupted").apply { initCause(cancelled) }
                            } catch (failure: Exception) {
                                context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().remove("adb-paired").apply()
                                throw failure
                            }
                        }).also { created = it }
                    }
                }
            }
        } catch (failure: Throwable) {
            created?.close()
            throw failure
        }
    }

    private fun transport(port: Int, identity: ScreenAdbIdentity.Identity): AdbConnection =
        AdbConnection.Builder("127.0.0.1", port).setApi(Build.VERSION.SDK_INT)
            .setPrivateKey(identity.key).setCertificate(identity.certificate).setDeviceName("mobby-screen").build()

    private fun verifyAuthorization(port: Int, identity: ScreenAdbIdentity.Identity, peerKey: ByteArray) {
        transport(port, identity).use { probe ->
            check(probe.connect(3, TimeUnit.SECONDS, true)) { "adb_authorization_timeout" }
            val actualKey = checkNotNull(probe.tlsPeerPublicKey).encoded
            check(MessageDigest.isEqual(peerKey, actualKey)) { "adb_daemon_changed" }
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

    @Suppress("DEPRECATION") internal suspend fun discover(context: Context, pairing: Boolean = false): Int = withTimeoutOrNull(if (pairing) 90_000 else 10_000) {
        suspendCancellableCoroutine { continuation ->
            val nsd = context.getSystemService(NsdManager::class.java)
            val queue = java.util.ArrayDeque<NsdServiceInfo>()
            var resolving = false
            lateinit var listener: NsdManager.DiscoveryListener
            fun stop() { runCatching { nsd.stopServiceDiscovery(listener) } }
            fun resolveNext() {
                if (!continuation.isActive || resolving || queue.isEmpty()) return
                resolving = true
                nsd.resolveService(queue.removeFirst(), object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolving = false; resolveNext() }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving = false
                        if (continuation.isActive && info.host?.let(::isLocal) == true && info.port in 1..65535) {
                            continuation.resume(info.port); stop()
                        } else resolveNext()
                    }
                })
            }
            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onServiceFound(info: NsdServiceInfo) { queue.add(info); resolveNext() }
                override fun onStartDiscoveryFailed(type: String, error: Int) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("adb_discovery_failed")); stop()
                }
                override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            }
            continuation.invokeOnCancellation { stop() }
            nsd.discoverServices(if (pairing) "_adb-tls-pairing._tcp." else "_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }
    } ?: throw java.util.concurrent.TimeoutException("adb_discovery_timeout")
}
