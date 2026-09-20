package com.libtermux.os.registry

import com.libtermux.os.distro.Distro

/**
 * Desktop environments available inside a distro container.
 *
 * Note on GNOME variants:
 *   GNOME runs on X11 via XWayland fallback disabled — proot has no Wayland compositor.
 *   Software rendering (Mesa/LLVMpipe) is forced since no GPU passthrough exists.
 *   For lightweight use, prefer GNOME_FLASHBACK over full GNOME.
 */
enum class DesktopEnvironment(
    val displayName: String,
    val aptPackages: List<String>,
    val startCommand: String,
    val extraEnvVars: Map<String, String> = emptyMap(),
    val fedoraPackages: List<String> = emptyList(),
    val alpinePackages: List<String> = emptyList(),
    val approximateSizeMb: Int = 0,
) {

    // ── Lightweight DEs ───────────────────────────────────────────────────

    XFCE4(
        displayName  = "XFCE4",
        aptPackages  = listOf("xfce4", "xfce4-goodies", "dbus-x11"),
        startCommand = "startxfce4",
        fedoraPackages = listOf("xfce4-session", "xfwm4", "xfce4-panel"),
        alpinePackages = listOf("xfce4", "xfce4-terminal", "dbus"),
        approximateSizeMb = 400,
    ),

    OPENBOX(
        displayName  = "Openbox (minimal)",
        aptPackages  = listOf("openbox", "dbus-x11", "tint2", "feh"),
        startCommand = "openbox-session",
        approximateSizeMb = 80,
    ),

    LXDE(
        displayName  = "LXDE",
        aptPackages  = listOf("lxde", "dbus-x11"),
        startCommand = "startlxde",
        approximateSizeMb = 250,
    ),

    LXQT(
        displayName  = "LXQt",
        aptPackages  = listOf("lxqt", "openbox", "dbus-x11"),
        startCommand = "startlxqt",
        approximateSizeMb = 350,
    ),

    MATE(
        displayName  = "MATE",
        aptPackages  = listOf("mate-desktop-environment-core", "dbus-x11"),
        startCommand = "mate-session",
        approximateSizeMb = 600,
    ),

    // ── GNOME variants ────────────────────────────────────────────────────

    /**
     * Full GNOME Shell on X11.
     *
     * Requires software rendering (LLVMpipe/Mesa) since proot has no GPU access.
     * Heavy: ~1.5 GB installed. Recommended only for devices with ≥4 GB RAM.
     *
     * Environment vars injected into xstartup:
     *   XDG_SESSION_TYPE=x11        → force X11, disable Wayland compositor
     *   GDK_BACKEND=x11             → force GTK apps to use X11 backend
     *   LIBGL_ALWAYS_SOFTWARE=1     → Mesa software renderer (LLVMpipe)
     *   MESA_GL_VERSION_OVERRIDE=3.3 → report OpenGL 3.3 to gnome-shell
     *   GNOME_SHELL_SLOWDOWN_FACTOR=1 → prevent startup timeout on slow devices
     */
    GNOME(
        displayName  = "GNOME (X11)",
        aptPackages  = listOf(
            "gnome-session",
            "gnome-shell",
            "gnome-terminal",
            "nautilus",
            "gnome-control-center",
            "gnome-tweaks",
            "adwaita-icon-theme",
            "gnome-backgrounds",
            "dbus-x11",
            "libgl1-mesa-dri",      // Mesa software rendering
            "mesa-utils",
        ),
        startCommand   = "gnome-session",
        extraEnvVars   = mapOf(
            "XDG_SESSION_TYPE"            to "x11",
            "GDK_BACKEND"                 to "x11",
            "LIBGL_ALWAYS_SOFTWARE"       to "1",
            "MESA_GL_VERSION_OVERRIDE"    to "3.3",
            "MESA_GLSL_VERSION_OVERRIDE"  to "330",
            "GNOME_SHELL_SLOWDOWN_FACTOR" to "1",
            "__GLX_VENDOR_LIBRARY_NAME"   to "mesa",
        ),
        fedoraPackages = listOf(
            "gnome-shell", "gnome-session", "gnome-terminal",
            "nautilus", "mesa-dri-drivers",
        ),
        approximateSizeMb = 1500,
    ),

    /**
     * GNOME Flashback — classic GNOME 2-style panel with Metacity WM.
     *
     * Much lighter than full GNOME Shell (~400 MB vs ~1.5 GB).
     * Uses Metacity (non-compositing) window manager — ideal for proot/VNC.
     * Recommended GNOME option for most Android devices.
     */
    GNOME_FLASHBACK(
        displayName  = "GNOME Flashback (recommended)",
        aptPackages  = listOf(
            "gnome-session",
            "gnome-session-flashback",
            "gnome-terminal",
            "nautilus",
            "metacity",
            "gnome-applets",
            "gnome-panel",
            "dbus-x11",
            "adwaita-icon-theme",
        ),
        startCommand   = "gnome-session --session=gnome-flashback-metacity",
        extraEnvVars   = mapOf(
            "XDG_SESSION_TYPE" to "x11",
            "GDK_BACKEND"      to "x11",
            "LIBGL_ALWAYS_SOFTWARE" to "1",
        ),
        approximateSizeMb = 450,
    ),

    /**
     * GNOME Classic — GNOME Shell with the classic-mode extension.
     *
     * Closer to full GNOME Shell but with a traditional taskbar/application menu.
     * Still requires software rendering. ~1 GB installed.
     */
    GNOME_CLASSIC(
        displayName  = "GNOME Classic",
        aptPackages  = listOf(
            "gnome-session",
            "gnome-shell",
            "gnome-shell-extensions",
            "gnome-shell-extension-classic-mode",
            "gnome-terminal",
            "dbus-x11",
            "libgl1-mesa-dri",
        ),
        startCommand   = "gnome-session --session=gnome-classic",
        extraEnvVars   = mapOf(
            "XDG_SESSION_TYPE"           to "x11",
            "GDK_BACKEND"                to "x11",
            "LIBGL_ALWAYS_SOFTWARE"      to "1",
            "MESA_GL_VERSION_OVERRIDE"   to "3.3",
            "MESA_GLSL_VERSION_OVERRIDE" to "330",
        ),
        approximateSizeMb = 1000,
    ),

    // ── CLI only ──────────────────────────────────────────────────────────

    NONE(
        displayName  = "No desktop (CLI only)",
        aptPackages  = emptyList(),
        startCommand = "",
        approximateSizeMb = 0,
    ),
}

/** VNC display resolution presets */
enum class DisplayResolution(val width: Int, val height: Int, val label: String) {
    HD_720P(1280, 720,    "1280×720  (HD)"),
    FHD_1080P(1920, 1080, "1920×1080 (FHD)"),
    TABLET_1200P(1920, 1200, "1920×1200 (Tablet)"),
    SMALL_800P(1280, 800,  "1280×800  (Small)"),
    CUSTOM(-1, -1,         "Custom"),
}

/**
 * Developer-declared configuration for a single supported distro.
 * Created via the [SupportedDistroBuilder] DSL inside [DistroRegistry].
 */
data class SupportedDistro(
    val distro: Distro,
    val guiEnabled: Boolean                    = false,
    val desktopEnvironment: DesktopEnvironment = DesktopEnvironment.XFCE4,
    val defaultResolution: DisplayResolution   = DisplayResolution.HD_720P,
    val customWidth: Int                       = 1280,
    val customHeight: Int                      = 720,
    val colorDepth: Int                        = 24,
    val vncPassword: String?                   = null,
    val vncDisplay: Int                        = 1,
    val startupCommands: List<String>          = emptyList(),
    val extraPackages: List<String>            = emptyList(),
    val extraBindMounts: List<String>          = emptyList(),
    val showInLauncher: Boolean                = true,
    val description: String                    = "",
) {
    val vncPort: Int get() = 5900 + vncDisplay

    val effectiveWidth: Int get() = if (defaultResolution == DisplayResolution.CUSTOM)
        customWidth else defaultResolution.width

    val effectiveHeight: Int get() = if (defaultResolution == DisplayResolution.CUSTOM)
        customHeight else defaultResolution.height

    /** Whether this distro uses a GNOME-family desktop */
    val isGnomeBased: Boolean get() = desktopEnvironment in listOf(
        DesktopEnvironment.GNOME,
        DesktopEnvironment.GNOME_FLASHBACK,
        DesktopEnvironment.GNOME_CLASSIC,
    )
}

/** DSL builder for [SupportedDistro] */
class SupportedDistroBuilder(private val distro: Distro) {
    var guiEnabled: Boolean                    = false
    var desktopEnvironment: DesktopEnvironment = DesktopEnvironment.XFCE4
    var defaultResolution: DisplayResolution   = DisplayResolution.HD_720P
    var customWidth: Int                       = 1280
    var customHeight: Int                      = 720
    var colorDepth: Int                        = 24
    var vncPassword: String?                   = null
    var vncDisplay: Int                        = 1
    var startupCommands: List<String>          = emptyList()
    var extraPackages: List<String>            = emptyList()
    var extraBindMounts: List<String>          = emptyList()
    var showInLauncher: Boolean                = true
    var description: String                    = distro.displayName

    fun build() = SupportedDistro(
        distro             = distro,
        guiEnabled         = guiEnabled,
        desktopEnvironment = desktopEnvironment,
        defaultResolution  = defaultResolution,
        customWidth        = customWidth,
        customHeight       = customHeight,
        colorDepth         = colorDepth,
        vncPassword        = vncPassword,
        vncDisplay         = vncDisplay,
        startupCommands    = startupCommands,
        extraPackages      = extraPackages,
        extraBindMounts    = extraBindMounts,
        showInLauncher     = showInLauncher,
        description        = description,
    )
}
