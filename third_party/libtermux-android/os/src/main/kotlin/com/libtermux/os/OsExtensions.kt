package com.libtermux.os

import com.libtermux.LibTermux

/**
 * Extension function that creates an [OsEnvironment] from a [LibTermux] instance.
 *
 * This avoids a circular module dependency — [LibTermux] lives in :core
 * (which has no Compose/proot/VNC dependencies), while [OsEnvironment] lives
 * in :os which depends on :core via api(project(":core")).
 *
 * Usage:
 * ```kotlin
 * val libtermux = LibTermux.init(context, termuxConfig { ... })
 *
 * // Create the OS environment
 * val os = libtermux.createOs {
 *     registry {
 *         distro(Distro.Kali) {
 *             guiEnabled         = true
 *             desktopEnvironment = DesktopEnvironment.XFCE4
 *             defaultResolution  = DisplayResolution.HD_720P
 *         }
 *         distro(Distro.Ubuntu2404) {
 *             guiEnabled = false
 *             extraPackages = listOf("python3", "nodejs")
 *         }
 *     }
 *     executionMode = ExecutionMode.AUTO
 * }
 *
 * // Setup a distro
 * os.setupDistro(Distro.Kali).collect { state -> ... }
 *
 * // Run commands
 * val result = os.execute(Distro.Kali, "nmap -sV 192.168.1.1")
 *
 * // GUI desktop
 * val session = os.createDesktopSession(Distro.Kali)
 * DistroDisplay(session = session)
 * ```
 */
fun LibTermux.createOs(block: OsConfigDsl.() -> Unit = {}): OsEnvironment {
    val config = OsConfigDsl().apply(block).build()
    return OsEnvironment(
        vfs      = this.vfs,
        executor = this.executor,
        config   = config,
    )
}
