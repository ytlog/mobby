package com.libtermux.os

import com.libtermux.os.registry.DistroRegistry
import com.libtermux.os.registry.DistroRegistryBuilder
import java.io.File

/**
 * Configuration for the OS/distro module.
 * Attach to [TermuxConfig] via the `os {}` DSL block.
 */
data class OsConfig(
    /**
     * Which distros this app declares as supported.
     * Only registered distros appear in [DistroLauncher] and
     * can be used via [OsEnvironment].
     *
     * An empty registry means all built-in distros are available
     * (open mode — useful for generic terminal apps).
     */
    val registry: DistroRegistry = DistroRegistry(),

    /** AUTO → chroot if rooted, proot otherwise */
    val executionMode: ExecutionMode = ExecutionMode.AUTO,

    /**
     * Root directory where distro rootfs folders are stored.
     * Defaults to filesDir/libtermux/distros.
     * Set to external storage if internal space is limited.
     */
    val distroStorageDir: File? = null,

    /** Download timeout for rootfs archives (default 10 min) */
    val downloadTimeoutMs: Long = 600_000L,

    /** Bind /sdcard into every container */
    val bindSdCard: Boolean = true,

    /**
     * Fake uid=0 inside proot container (-0 flag).
     * Required for apt/apk/dnf to work without real root.
     */
    val fakeRoot: Boolean = true,

    /**
     * Additional bind mounts injected into every session.
     * Format: "hostPath:containerPath" or just "path".
     */
    val extraBindMounts: List<String> = emptyList(),

    /** Run distro setup commands after first extraction */
    val runSetupCommands: Boolean = true,

    /** Write /etc/resolv.conf after extraction */
    val configureDns: Boolean = true,

    /** DNS server written to resolv.conf */
    val dnsServer: String = "1.1.1.1",
)

enum class ExecutionMode {
    /** Auto-detect: real chroot if rooted, proot otherwise */
    AUTO,
    /** Force proot even when root is available */
    PROOT,
    /** Force real chroot — throws IllegalStateException if not rooted */
    REAL_CHROOT,
}

// ── DSL ───────────────────────────────────────────────────────────────────────

@TermuxDsl
class OsConfigDsl {
    var executionMode: ExecutionMode         = ExecutionMode.AUTO
    var bindSdCard: Boolean                  = true
    var fakeRoot: Boolean                    = true
    var runSetupCommands: Boolean            = true
    var configureDns: Boolean                = true
    var dnsServer: String                    = "1.1.1.1"
    var downloadTimeoutMs: Long              = 600_000L
    var distroStorageDir: File?              = null
    val extraBindMounts: MutableList<String> = mutableListOf()

    private var _registry: DistroRegistry   = DistroRegistry()

    /**
     * Declare which distros your app supports.
     *
     * ```kotlin
     * os {
     *     registry {
     *         distro(Distro.Kali) {
     *             guiEnabled         = true
     *             desktopEnvironment = DesktopEnvironment.XFCE4
     *         }
     *     }
     * }
     * ```
     */
    fun registry(block: DistroRegistryBuilder.() -> Unit) {
        _registry = DistroRegistryBuilder().apply(block).build()
    }

    fun bind(hostPath: String, containerPath: String = hostPath) {
        extraBindMounts.add("$hostPath:$containerPath")
    }

    internal fun build() = OsConfig(
        registry          = _registry,
        executionMode     = executionMode,
        distroStorageDir  = distroStorageDir,
        downloadTimeoutMs = downloadTimeoutMs,
        bindSdCard        = bindSdCard,
        fakeRoot          = fakeRoot,
        extraBindMounts   = extraBindMounts.toList(),
        runSetupCommands  = runSetupCommands,
        configureDns      = configureDns,
        dnsServer         = dnsServer,
    )
}
