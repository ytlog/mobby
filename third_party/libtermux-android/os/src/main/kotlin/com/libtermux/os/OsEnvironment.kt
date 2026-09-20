package com.libtermux.os

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.os.chroot.ChrootRunner
import com.libtermux.os.distro.Distro
import com.libtermux.os.distro.DistroSetupState
import com.libtermux.os.gui.DesktopSession
import com.libtermux.os.proot.ProotRunner
import com.libtermux.os.registry.DistroRegistry
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.flow.Flow

/**
 * Main public API for the OS/distro module.
 *
 * Auto-detects root and selects the best backend:
 *  • Rooted  → [ChrootRunner] — real kernel chroot, native speed
 *  • No root → [ProotRunner]  — userspace proot, no root needed
 *
 * Usage:
 * ```kotlin
 * // In LibTermux init:
 * termuxConfig {
 *     os {
 *         registry {
 *             distro(Distro.Kali) {
 *                 guiEnabled = true
 *                 desktopEnvironment = DesktopEnvironment.XFCE4
 *             }
 *         }
 *     }
 * }
 *
 * // Setup
 * libtermux.os.setupDistro(Distro.Kali).collect { ... }
 *
 * // CLI command
 * val result = libtermux.os.execute(Distro.Kali, "nmap -sV 192.168.1.1")
 *
 * // GUI desktop
 * val session = libtermux.os.createDesktopSession(Distro.Kali)
 * session.start(settings)
 * DistroDisplay(session = session)
 * ```
 */
class OsEnvironment internal constructor(
    private val vfs:      VirtualFileSystem,
    private val executor: CommandExecutor,
    val config:           OsConfig = OsConfig(),
) {
    /** Declared distro registry from [OsConfig] */
    val registry: DistroRegistry = config.registry

    /** Resolved execution mode (AUTO expands to PROOT or REAL_CHROOT) */
    val executionMode: ExecutionMode by lazy {
        when (config.executionMode) {
            ExecutionMode.AUTO -> if (RootUtils.isRooted) {
                TermuxLogger.i("OsEnvironment → REAL_CHROOT (rooted)")
                ExecutionMode.REAL_CHROOT
            } else {
                TermuxLogger.i("OsEnvironment → PROOT (no root)")
                ExecutionMode.PROOT
            }
            ExecutionMode.REAL_CHROOT -> {
                if (!RootUtils.isRooted)
                    TermuxLogger.w("REAL_CHROOT forced but device is not rooted!")
                ExecutionMode.REAL_CHROOT
            }
            ExecutionMode.PROOT -> ExecutionMode.PROOT
        }
    }

    val isUsingRealChroot: Boolean get() = executionMode == ExecutionMode.REAL_CHROOT

    private val prootRunner  by lazy { ProotRunner(vfs, executor, config) }
    private val chrootRunner by lazy { ChrootRunner(vfs, executor, config) }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validate that [distro] is declared in the registry.
     * If the registry is empty (open mode), all distros are allowed.
     */
    private fun requireRegistered(distro: Distro) {
        if (!registry.isEmpty() && !registry.supports(distro)) {
            throw IllegalArgumentException(
                "${distro.displayName} is not registered in DistroRegistry. " +
                "Add it via os { registry { distro(Distro.${distro::class.simpleName}) { ... } } }"
            )
        }
    }

    private fun requireInstalled(distro: Distro) {
        requireRegistered(distro)
        if (!isInstalled(distro)) {
            throw IllegalStateException(
                "${distro.displayName} is not installed. " +
                "Call setupDistro(Distro.${distro::class.simpleName}) first."
            )
        }
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    /**
     * Download, extract, and configure a distro.
     * Safe to call multiple times — skips if already installed.
     *
     * @param forceReinstall Delete and reinstall even if already installed
     */
    fun setupDistro(
        distro: Distro,
        forceReinstall: Boolean = false,
    ): Flow<DistroSetupState> {
        requireRegistered(distro)
        return when (executionMode) {
            ExecutionMode.REAL_CHROOT -> chrootRunner.setup(distro, forceReinstall)
            else                      -> prootRunner.setup(distro, forceReinstall)
        }
    }

    fun isInstalled(distro: Distro): Boolean = when (executionMode) {
        ExecutionMode.REAL_CHROOT -> chrootRunner.isInstalled(distro)
        else                      -> prootRunner.isInstalled(distro)
    }

    /** All currently installed distros (from the registry) */
    fun installedDistros(): List<Distro> {
        val source = if (registry.isEmpty()) Distro.all else registry.all.map { it.distro }
        return source.filter { isInstalled(it) }
    }

    /** Delete a distro's rootfs. Does NOT affect the Termux bootstrap. */
    fun uninstall(distro: Distro) {
        requireRegistered(distro)
        when (executionMode) {
            ExecutionMode.REAL_CHROOT -> chrootRunner.uninstall(distro)
            else                      -> prootRunner.uninstall(distro)
        }
    }

    // ── Execution ─────────────────────────────────────────────────────────

    /**
     * Execute a shell command inside [distro].
     *
     * @param distro   Must be installed
     * @param command  Shell command string
     * @param workDir  Working directory inside container (default: /root)
     */
    suspend fun execute(
        distro:  Distro,
        command: String,
        workDir: String = "/root",
    ): ExecutionResult {
        requireInstalled(distro)
        return when (executionMode) {
            ExecutionMode.REAL_CHROOT -> chrootRunner.execute(distro, command, workDir)
            else                      -> prootRunner.execute(distro, command, workDir)
        }
    }

    /**
     * Start an interactive login shell inside [distro].
     * Returns the raw [Process] — attach to a PTY/terminal view for I/O.
     */
    suspend fun login(distro: Distro): Process {
        requireInstalled(distro)
        return when (executionMode) {
            ExecutionMode.REAL_CHROOT -> chrootRunner.login(distro)
            else                      -> prootRunner.login(distro)
        }
    }

    // ── GUI Desktop ───────────────────────────────────────────────────────

    /**
     * Create a [DesktopSession] for [distro].
     *
     * The distro must have `guiEnabled = true` in its [SupportedDistro]
     * declaration. The session is NOT started here — call [DesktopSession.start].
     *
     * ```kotlin
     * val session = libtermux.os.createDesktopSession(Distro.Kali)
     * session.start(settings)
     *
     * // In Compose:
     * DistroDisplay(session = session)
     * ```
     */
    fun createDesktopSession(distro: Distro): DesktopSession {
        requireRegistered(distro)

        val supportedDistro = registry[distro]
            ?: throw IllegalArgumentException(
                "${distro.displayName} is not in the registry. " +
                "Register it via os { registry { distro(Distro.${distro::class.simpleName}) } }"
            )

        if (!supportedDistro.guiEnabled) {
            throw IllegalStateException(
                "${distro.displayName} does not have guiEnabled = true. " +
                "Set guiEnabled = true in its registry declaration."
            )
        }

        return DesktopSession(
            distro          = distro,
            supportedDistro = supportedDistro,
            executor        = executor,
            runInDistro     = { command ->
                execute(distro, command).stdout
            },
        )
    }

    // ── Convenience ───────────────────────────────────────────────────────

    /** Install packages via the distro's native package manager */
    suspend fun install(distro: Distro, vararg packages: String): ExecutionResult {
        val cmd = when (distro) {
            Distro.Alpine   -> "apk add --no-cache ${packages.joinToString(" ")}"
            Distro.Fedora40 -> "dnf install -y ${packages.joinToString(" ")}"
            else            -> "DEBIAN_FRONTEND=noninteractive apt-get install -y ${packages.joinToString(" ")}"
        }
        return execute(distro, cmd)
    }

    /** Update package lists */
    suspend fun update(distro: Distro): ExecutionResult {
        val cmd = when (distro) {
            Distro.Alpine   -> "apk update"
            Distro.Fedora40 -> "dnf check-update || true"
            else            -> "apt-get update -y"
        }
        return execute(distro, cmd)
    }

    /** Run a Python script inside the distro */
    suspend fun python(
        distro:  Distro,
        script:  String,
        args:    List<String> = emptyList(),
    ): ExecutionResult {
        val path = "/tmp/libtermux_${System.currentTimeMillis()}.py"
        execute(distro, "cat > $path << 'PYEOF'\n$script\nPYEOF")
        return execute(distro, "python3 $path ${args.joinToString(" ")}")
    }

    /** Check if a binary exists inside the distro */
    suspend fun hasBinary(distro: Distro, binary: String): Boolean =
        execute(distro, "command -v $binary >/dev/null 2>&1 && echo yes || echo no")
            .stdout.trim() == "yes"
}
