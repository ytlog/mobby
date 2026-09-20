package com.libtermux.os.registry

import com.libtermux.os.distro.Distro

/**
 * Registry of distros that a developer has declared as supported in their app.
 *
 * Only declared distros appear in [DistroLauncher] and can be used via
 * [OsEnvironment]. This prevents users from accidentally installing a 2GB
 * rootfs the app was never designed to use.
 *
 * Usage in LibTermux init:
 * ```kotlin
 * LibTermux.init(context, termuxConfig {
 *     os {
 *         registry {
 *             // Only Kali with GUI
 *             distro(Distro.Kali) {
 *                 guiEnabled         = true
 *                 desktopEnvironment = DesktopEnvironment.XFCE4
 *                 defaultResolution  = DisplayResolution.HD_720P
 *                 description        = "Full Kali Linux penetration testing suite"
 *                 extraPackages      = listOf("nmap", "metasploit-framework")
 *                 startupCommands    = listOf("service postgresql start")
 *             }
 *             // Ubuntu without GUI (server mode)
 *             distro(Distro.Ubuntu2404) {
 *                 guiEnabled  = false
 *                 description = "Ubuntu development environment"
 *                 extraPackages = listOf("python3", "nodejs", "git")
 *             }
 *         }
 *     }
 * })
 * ```
 */
class DistroRegistry {

    private val _entries = mutableMapOf<String, SupportedDistro>()

    /** All declared distros in insertion order */
    val all: List<SupportedDistro> get() = _entries.values.toList()

    /** Distros visible in the launcher UI */
    val launcherEntries: List<SupportedDistro> get() = all.filter { it.showInLauncher }

    /** Distros with GUI enabled */
    val guiDistros: List<SupportedDistro> get() = all.filter { it.guiEnabled }

    /**
     * Declare a distro as supported with a configuration DSL.
     * Call this once per distro during initialization.
     */
    fun distro(
        distro: Distro,
        block: SupportedDistroBuilder.() -> Unit = {},
    ): DistroRegistry {
        val entry = SupportedDistroBuilder(distro).apply(block).build()
        _entries[distro.id] = entry
        return this
    }

    /**
     * Declare a pre-built [SupportedDistro] directly.
     */
    fun distro(entry: SupportedDistro): DistroRegistry {
        _entries[entry.distro.id] = entry
        return this
    }

    /**
     * Returns the [SupportedDistro] for [distro], or null if not declared.
     */
    operator fun get(distro: Distro): SupportedDistro? = _entries[distro.id]

    /**
     * Returns true if [distro] has been declared as supported.
     */
    fun supports(distro: Distro): Boolean = distro.id in _entries

    /**
     * Returns true if [distro] has GUI support declared.
     */
    fun hasGui(distro: Distro): Boolean = _entries[distro.id]?.guiEnabled == true

    fun isEmpty(): Boolean = _entries.isEmpty()
    fun size(): Int = _entries.size

    override fun toString(): String =
        "DistroRegistry(${_entries.keys.joinToString()})"
}

/** DSL for declaring the registry inside [OsConfigDsl] */
class DistroRegistryBuilder {
    private val registry = DistroRegistry()

    fun distro(
        distro: Distro,
        block: SupportedDistroBuilder.() -> Unit = {},
    ) = apply { registry.distro(distro, block) }

    fun build(): DistroRegistry = registry
}
