package com.libtermux.os.gui

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.os.distro.Distro
import com.libtermux.os.gui.vnc.VncClient
import com.libtermux.os.gui.vnc.VncState
import com.libtermux.os.registry.DesktopEnvironment
import com.libtermux.os.registry.SupportedDistro
import com.libtermux.os.settings.DistroRuntimeSettings
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Lifecycle states of a desktop session */
sealed class DesktopSessionState {
    object Idle                                                   : DesktopSessionState()
    object InstallingDesktop                                      : DesktopSessionState()
    data class InstallingPackage(val pkg: String)                 : DesktopSessionState()
    object StartingVncServer                                      : DesktopSessionState()
    data class WaitingForServer(val attempt: Int)                 : DesktopSessionState()
    object ConnectingVnc                                          : DesktopSessionState()
    object Running                                                : DesktopSessionState()
    data class Failed(val reason: String, val cause: Throwable? = null) : DesktopSessionState()
    object Stopped                                                : DesktopSessionState()
}

/**
 * Manages the full lifecycle of a graphical distro desktop session.
 *
 * Supports all desktop environments including the GNOME family:
 *  - XFCE4, LXDE, LXQt, MATE, Openbox
 *  - GNOME (full, X11 + software rendering)
 *  - GNOME Flashback (lightweight, recommended for proot)
 *  - GNOME Classic
 *
 * GNOME requires special handling:
 *  - Forces X11 backend (no Wayland in proot)
 *  - Enables Mesa software rendering (LIBGL_ALWAYS_SOFTWARE=1)
 *  - Sets MESA_GL_VERSION_OVERRIDE so gnome-shell accepts the renderer
 *  - Uses GNOME_SHELL_SLOWDOWN_FACTOR=1 to prevent startup timeout
 */
class DesktopSession(
    val distro:          Distro,
    val supportedDistro: SupportedDistro,
    private val executor: CommandExecutor,
    private val runInDistro: suspend (String) -> String,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _sessionState = MutableStateFlow<DesktopSessionState>(DesktopSessionState.Idle)
    val sessionState: StateFlow<DesktopSessionState> = _sessionState.asStateFlow()

    val vnc = VncClient(
        host     = "127.0.0.1",
        port     = supportedDistro.vncPort,
        password = supportedDistro.vncPassword ?: "",
    )

    val isRunning: Boolean get() = _sessionState.value == DesktopSessionState.Running

    val de: DesktopEnvironment get() = supportedDistro.desktopEnvironment

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start(settings: DistroRuntimeSettings) {
        scope.launch {
            try {
                _sessionState.value = DesktopSessionState.InstallingDesktop
                ensureGuiInstalled()

                _sessionState.value = DesktopSessionState.StartingVncServer
                writeXstartup(settings)
                killExistingVnc(supportedDistro.vncDisplay)
                startVncServer(settings)
                waitForVncPort(settings.vncPort)

                _sessionState.value = DesktopSessionState.ConnectingVnc
                vnc.connect()

                vnc.state.collect { vncState ->
                    when (vncState) {
                        is VncState.Connected -> _sessionState.value = DesktopSessionState.Running
                        is VncState.Failed    -> _sessionState.value = DesktopSessionState.Failed(vncState.reason)
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                TermuxLogger.e("DesktopSession.start failed: ${distro.id}", e)
                _sessionState.value = DesktopSessionState.Failed(e.message ?: "Unknown error", e)
            }
        }
    }

    suspend fun stop() {
        vnc.disconnect()
        killExistingVnc(supportedDistro.vncDisplay)
        scope.cancel()
        _sessionState.value = DesktopSessionState.Stopped
        TermuxLogger.i("DesktopSession stopped: ${distro.id}")
    }

    // ── GUI installation ──────────────────────────────────────────────────

    private suspend fun ensureGuiInstalled() {
        // Check if VNC server already installed
        val vncPresent = runInDistro("command -v Xvnc >/dev/null 2>&1 && echo yes || echo no").trim()
        // Check if DE already installed
        val dePresent  = when (de) {
            DesktopEnvironment.NONE -> true
            DesktopEnvironment.XFCE4,
            DesktopEnvironment.LXDE,
            DesktopEnvironment.LXQT,
            DesktopEnvironment.MATE     -> runInDistro("command -v ${de.startCommand.split(" ").first()} >/dev/null 2>&1 && echo yes || echo no").trim() == "yes"
            DesktopEnvironment.OPENBOX  -> runInDistro("command -v openbox >/dev/null 2>&1 && echo yes || echo no").trim() == "yes"
            DesktopEnvironment.GNOME,
            DesktopEnvironment.GNOME_FLASHBACK,
            DesktopEnvironment.GNOME_CLASSIC -> runInDistro("command -v gnome-session >/dev/null 2>&1 && echo yes || echo no").trim() == "yes"
        }

        if (vncPresent == "yes" && dePresent) {
            TermuxLogger.i("GUI already installed for ${distro.id}")
            return
        }

        val pkgManager = detectPackageManager()
        val packages   = buildGnomeAwarePackageList(pkgManager)

        if (packages.isEmpty()) return

        TermuxLogger.i("Installing ${de.displayName} (${de.approximateSizeMb} MB approx)")

        packages.chunked(5).forEach { chunk ->
            val pkgStr = chunk.joinToString(" ")
            _sessionState.value = DesktopSessionState.InstallingPackage(pkgStr)
            val installCmd = when (pkgManager) {
                "apt"  -> "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgStr 2>&1 || true"
                "apk"  -> "apk add --no-cache $pkgStr 2>&1 || true"
                "dnf"  -> "dnf install -y $pkgStr 2>&1 || true"
                else   -> "apt-get install -y $pkgStr 2>&1 || true"
            }
            runInDistro(installCmd)
        }

        // Extra setup for GNOME
        if (supportedDistro.isGnomeBased) {
            setupGnomeExtras()
        }
    }

    private fun buildGnomeAwarePackageList(pkgManager: String): List<String> {
        val basePackages = mutableListOf<String>()

        // VNC server
        basePackages += when (pkgManager) {
            "apk"  -> listOf("tigervnc")
            "dnf"  -> listOf("tigervnc-server")
            else   -> listOf("tigervnc-standalone-server", "tigervnc-common")
        }
        basePackages += "dbus-x11"

        // Desktop environment packages
        basePackages += when (pkgManager) {
            "apk"  -> de.alpinePackages.ifEmpty { de.aptPackages }
            "dnf"  -> de.fedoraPackages.ifEmpty { de.aptPackages }
            else   -> de.aptPackages
        }

        // Extra packages declared by developer
        basePackages += supportedDistro.extraPackages

        return basePackages.distinct()
    }

    private suspend fun setupGnomeExtras() {
        // Disable gnome-initial-setup (runs on first login, breaks VNC)
        runInDistro("mkdir -p /root/.config && touch /root/.config/gnome-initial-setup-done")
        // Disable screen lock (causes issues in VNC)
        runInDistro(
            "command -v gsettings >/dev/null 2>&1 && " +
            "DBUS_SESSION_BUS_ADDRESS=autolaunch: gsettings set org.gnome.desktop.screensaver lock-enabled false 2>/dev/null || true"
        )
        // Set display scaling to 1x
        runInDistro(
            "command -v gsettings >/dev/null 2>&1 && " +
            "DBUS_SESSION_BUS_ADDRESS=autolaunch: gsettings set org.gnome.desktop.interface scaling-factor 1 2>/dev/null || true"
        )
        TermuxLogger.d("GNOME extras configured")
    }

    // ── Xstartup ─────────────────────────────────────────────────────────

    /**
     * Write ~/.vnc/xstartup inside the distro.
     *
     * GNOME needs special environment variables:
     *  - XDG_SESSION_TYPE=x11 → prevent Wayland from being attempted
     *  - GDK_BACKEND=x11      → force GTK to use X11 (not Wayland/Broadway)
     *  - LIBGL_ALWAYS_SOFTWARE=1 → Mesa LLVMpipe software renderer (no GPU in proot)
     *  - MESA_GL_VERSION_OVERRIDE=3.3 → gnome-shell requires OpenGL 3.3+
     *  - GNOME_SHELL_SLOWDOWN_FACTOR=1 → prevent watchdog timeout on slow devices
     */
    private suspend fun writeXstartup(settings: DistroRuntimeSettings) {
        runInDistro("mkdir -p /root/.vnc")

        val envBlock = buildEnvBlock()
        val runtimeDir = buildRuntimeDirSetup()
        val sessionCmd  = buildSessionCommand()

        val script = """
            #!/bin/sh
            unset SESSION_MANAGER
            unset DBUS_SESSION_BUS_ADDRESS
            $envBlock
            $runtimeDir
            $sessionCmd
        """.trimIndent()

        // Write via heredoc to avoid shell escaping issues
        runInDistro("cat > /root/.vnc/xstartup << 'XEOF'\n$script\nXEOF")
        runInDistro("chmod +x /root/.vnc/xstartup")

        TermuxLogger.d("xstartup written for ${de.displayName}")
    }

    private fun buildEnvBlock(): String {
        val lines = mutableListOf<String>()

        // Add DE-specific env vars
        de.extraEnvVars.forEach { (key, value) ->
            lines += "export $key=$value"
        }

        // GNOME: additional runtime setup
        if (supportedDistro.isGnomeBased) {
            lines += "export DCONF_PROFILE=user"
            lines += "export XDG_CONFIG_DIRS=/etc/xdg"
            lines += "export XDG_DATA_DIRS=/usr/local/share:/usr/share"
        }

        return lines.joinToString("\n")
    }

    private fun buildRuntimeDirSetup(): String = """
        export XDG_RUNTIME_DIR=/tmp/runtime-$$
        mkdir -p "${"$"}{XDG_RUNTIME_DIR}"
        chmod 700 "${"$"}{XDG_RUNTIME_DIR}"
    """.trimIndent()

    private fun buildSessionCommand(): String {
        if (de == DesktopEnvironment.NONE) return ""

        return if (supportedDistro.isGnomeBased) {
            // GNOME needs dbus-launch to create a session bus, then start gnome-session
            // The --exit-with-session flag ensures cleanup when session ends
            "exec dbus-launch --exit-with-session ${de.startCommand} --debug 2>/tmp/gnome-session.log"
        } else {
            "exec dbus-launch --exit-with-session ${de.startCommand}"
        }
    }

    // ── VNC server management ─────────────────────────────────────────────

    private suspend fun killExistingVnc(display: Int) {
        runCatching {
            runInDistro("vncserver -kill :$display 2>/dev/null || true")
            runInDistro("pkill -f 'Xvnc :$display' 2>/dev/null || true")
            if (supportedDistro.isGnomeBased) {
                runInDistro("pkill -f 'gnome-session' 2>/dev/null || true")
                runInDistro("pkill -f 'gnome-shell' 2>/dev/null || true")
            }
        }
        delay(500)
    }

    private fun startVncServer(settings: DistroRuntimeSettings) {
        val display  = supportedDistro.vncDisplay
        val geo      = "${settings.displayWidth}x${settings.displayHeight}"
        val depth    = settings.colorDepth
        val security = if (settings.vncPassword.isNotEmpty())
            "-PasswordFile /root/.vnc/passwd" else "-SecurityTypes None"

        scope.launch(Dispatchers.IO) {
            runCatching {
                // For GNOME: use Xvnc directly and start session separately
                // to have better control over environment variables
                if (supportedDistro.isGnomeBased) {
                    startGnomeVncServer(display, geo, depth, security, settings)
                } else {
                    runInDistro(
                        "vncserver :$display -geometry $geo -depth $depth " +
                        "-localhost no $security -fg 2>/tmp/vnc.log &"
                    )
                }
            }
        }
    }

    private suspend fun startGnomeVncServer(
        display: Int, geo: String, depth: Int,
        security: String, settings: DistroRuntimeSettings,
    ) {
        // Start Xvnc first
        runInDistro(
            "Xvnc :$display -geometry $geo -depth $depth " +
            "-localhost no $security " +
            "-nolisten tcp 2>/tmp/xvnc.log &"
        )
        delay(1000) // Wait for Xvnc to start

        // Build GNOME env string
        val gnomeEnv = de.extraEnvVars
            .entries.joinToString(" ") { "${it.key}=${it.value}" }

        // Start GNOME session on the Xvnc display
        runInDistro(
            "export DISPLAY=:$display && " +
            "$gnomeEnv " +
            "export XDG_RUNTIME_DIR=/tmp/runtime-gnome && " +
            "mkdir -p \$XDG_RUNTIME_DIR && chmod 700 \$XDG_RUNTIME_DIR && " +
            "dbus-launch --exit-with-session ${de.startCommand} " +
            "> /tmp/gnome-session.log 2>&1 &"
        )
    }

    private suspend fun waitForVncPort(port: Int, maxAttempts: Int = 45) {
        repeat(maxAttempts) { attempt ->
            _sessionState.value = DesktopSessionState.WaitingForServer(attempt + 1)
            runCatching {
                java.net.Socket("127.0.0.1", port).close()
                TermuxLogger.i("VNC port $port ready after ${attempt + 1}s")
                return
            }
            delay(1000)
        }
        throw IllegalStateException(
            "VNC server did not start on port $port after ${maxAttempts}s. " +
            "Check /tmp/vnc.log inside the distro for details."
        )
    }

    private suspend fun detectPackageManager(): String {
        val apt = runInDistro("command -v apt-get 2>/dev/null && echo apt || true").trim()
        if (apt.contains("apt")) return "apt"
        val apk = runInDistro("command -v apk 2>/dev/null && echo apk || true").trim()
        if (apk.contains("apk")) return "apk"
        val dnf = runInDistro("command -v dnf 2>/dev/null && echo dnf || true").trim()
        if (dnf.contains("dnf")) return "dnf"
        return "apt"
    }
}
