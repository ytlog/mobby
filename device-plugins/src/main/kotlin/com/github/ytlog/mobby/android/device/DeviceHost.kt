package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings
import com.github.ytlog.mobby.android.deviceinteraction.model.*

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.github.ytlog.mobby.android.runtime.api.CapabilityRef
import com.github.ytlog.mobby.android.runtime.api.PluginAccessKind
import com.github.ytlog.mobby.android.runtime.api.PluginGrantSummary
import com.github.ytlog.mobby.android.runtime.api.PluginSummary
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object DeviceHost {
    fun summaries(context: Context): List<PluginSummary> = DeviceCatalog.all.map { spec ->
        val permissions = if (spec.id == "media") mediaPermissions() else spec.permissions
        val available = granted(context, spec.ref)
        val grant = spec.grant?.let { grant ->
            val ref = spec.grantRef!!
            PluginGrantSummary(CapabilityRef(ref), grant.label, granted(context, ref), grant.permissions, reason(context, ref))
        }
        PluginSummary(
            CapabilityRef(spec.ref), spec.name, spec.description, spec.category, available,
            when (spec.access) {
                DeviceAccess.NONE -> PluginAccessKind.NONE
                DeviceAccess.RUNTIME -> PluginAccessKind.RUNTIME
                DeviceAccess.ACCESSIBILITY -> PluginAccessKind.ACCESSIBILITY
                DeviceAccess.DOCUMENT_TREE -> PluginAccessKind.DOCUMENT_TREE
            },
            permissions, grant, reason(context, spec.ref),
        )
    }
    fun granted(context: Context, ref: String): Boolean {
        val spec = DeviceCatalog.all.firstOrNull { it.ref == ref || it.grantRef == ref } ?: return false
        if (spec.grantRef == ref) return spec.grant!!.permissions.all { allowed(context, it) }
        return when (spec.id) {
            "screen" -> ScreenAccessService.connected()
            "media" -> mediaGranted(context)
            "storage" -> DeviceStorage.tree(context) != null
            "location" -> allowed(context, Manifest.permission.ACCESS_COARSE_LOCATION) || allowed(context, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> spec.permissions.all { allowed(context, it) }
        }
    }
    fun reason(context: Context, ref: String): String? {
        if (granted(context, ref)) return null
        val spec = DeviceCatalog.all.firstOrNull { it.ref == ref || it.grantRef == ref } ?: return AppStrings.unknownPlugin
        return when {
            spec.grantRef == ref -> AppStrings.allowForFirst(spec.name, spec.grant!!.label)
            spec.id == "screen" -> AppStrings.enableMobbySScreenAccessibilityServiceInSystemSettings
            spec.id == "storage" -> AppStrings.selectADirectoryToAllowAccess
            spec.id == "media" -> AppStrings.allowAccessToPhotosVideosOrAudioInYour
            else -> AppStrings.allowAccessToFirst(spec.name)
        }
    }
    fun mediaPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.READ_MEDIA_AUDIO,
        )
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private fun mediaGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34 && allowed(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return true
        return mediaPermissions().any { allowed(context, it) }
    }
    private fun allowed(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context, root: File, inbox: File, workspace: File, node: String, refs: Set<String>, operations: DeviceOperationPort, registerResource: (File, String) -> String, exportResource: (String) -> String, isCancelled: () -> Boolean = { false }): DeviceSession =
        openDeviceSession(refs, ScreenAccessService::stay) {
            val token = DeviceCommands.token()
            val allow = DeviceCatalog.allow(refs)
            val gate = DeviceActionGate(isCancelled)
            val actions = DeviceActions(context, inbox, workspace, gate, DeviceResourceRegistrar(registerResource))
            val bridge = DeviceBridge(gate::close) { line ->
                DeviceCommands.handle(line, token, allow, operations, gate::checkActive, exportResource) { plugin, action, args, execution -> actions.perform(plugin, action, args, execution) }
            }
            try {
                bridge to DeviceSkillPack.write(root, node, bridge.port, token, refs)
            } catch (error: Throwable) {
                bridge.close()
                throw error
            }
        }
}

internal fun openDeviceSession(
    refs: Set<String>,
    keepScreenOn: () -> AutoCloseable,
    open: () -> Pair<DeviceBridge, List<File>>,
): DeviceSession {
    val held = if ("plugin:device:screen" in refs) keepScreenOn() else null
    return try {
        val (bridge, skills) = open()
        DeviceSession(bridge, skills, held)
    } catch (error: Throwable) {
        held?.close()
        throw error
    }
}

class DeviceSession internal constructor(
    private val bridge: DeviceBridge,
    val skills: List<File>,
    private val awake: AutoCloseable? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    fun awaitIdle(checkActive: () -> Unit) = bridge.awaitIdle(checkActive)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            bridge.close()
        } finally {
            awake?.close()
        }
    }
}

internal class DeviceBridge(
    private val onClose: () -> Unit = {},
    private val handle: (String) -> String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    private val socketLock = Any()
    private var accepted: java.net.Socket? = null
    private val worker = thread(name = "device-bridge", isDaemon = true) {
        while (running.get()) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { continue }
            synchronized(socketLock) {
                if (running.get()) accepted = socket else socket.close()
            }
            try {
                socket.use { active ->
                    active.soTimeout = 15_000
                    val input = active.getInputStream()
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        if (byte == -1 || byte == 10) break
                        if (bytes.size() >= 65_536) throw java.io.IOException("Device request exceeds limit")
                        bytes.write(byte)
                    }
                    if (bytes.size() == 0 || !running.get()) return@use
                    val response = handle(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString())
                    active.getOutputStream().write((response + "\n").toByteArray())
                }
            } catch (_: java.io.IOException) {
                // A disconnected or cancelled client must not kill the command server.
            } finally {
                synchronized(socketLock) { if (accepted === socket) accepted = null }
            }
        }
    }
    fun awaitIdle(checkActive: () -> Unit) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(125)
        while (synchronized(socketLock) { accepted != null }) {
            checkActive()
            if (System.nanoTime() >= deadline) deviceFailure(DeviceErrorCode.TIMEOUT)
            Thread.sleep(25)
        }
    }
    override fun close() {
        onClose()
        running.set(false)
        server.close()
        synchronized(socketLock) { accepted?.close() }
        // An already dispatched Android gesture completes through its callback; do not block main.
    }
}
