package com.libtermux

import android.content.Context
import com.libtermux.bootstrap.BootstrapInstaller
import com.libtermux.bridge.TermuxBridge
import com.libtermux.executor.CommandExecutor
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.os.RootUtils
import com.libtermux.pkg.PackageManager
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.flow.Flow

/**
 * Main entry point for LibTermux.
 *
 * ```kotlin
 * // Initialize once (e.g. in Application.onCreate)
 * val libtermux = LibTermux.init(context, termuxConfig {
 *     autoInstall = true
 *     logLevel    = LogLevel.DEBUG
 * })
 *
 * // Bootstrap install (required before any command)
 * libtermux.install().collect { state -> ... }
 *
 * // Run a command in the Termux environment
 * val result = libtermux.bridge.bash("echo hello")
 *
 * // Set up and use a Linux distro (requires :os module)
 * // val os = libtermux.createOs { registry { distro(Distro.Kali) { ... } } }
 * // os.setupDistro(Distro.Kali).collect { ... }
 * ```
 */
class LibTermux private constructor(
    val context: Context,
    val config:  TermuxConfig,
    val vfs:     VirtualFileSystem,
) {
    val executor: CommandExecutor by lazy {
        CommandExecutor(config, vfs)
    }

    val bridge: TermuxBridge by lazy {
        TermuxBridge(
            executor   = executor,
            pkgManager = packageManager,
            vfs        = vfs,
        )
    }

    val packageManager: PackageManager by lazy {
        PackageManager(executor)
    }

    val installer: BootstrapInstaller by lazy {
        BootstrapInstaller(context, config, vfs)
    }

    /**
     * Install the Termux bootstrap environment.
     * Must be collected before calling any [bridge] methods.
     *
     * When [TermuxConfig.bootstrapProvider] is set, this does NOT download
     * anything — binaries already shipped inside the APK (via a
     * `bootstrap-<abi>` dependency) at install time. This just emits a
     * quick verification flow instead of the legacy network-download flow.
     */
    fun install(forceReinstall: Boolean = false): Flow<com.libtermux.bootstrap.InstallState> {
        val provider = config.bootstrapProvider
        if (provider != null) {
            return kotlinx.coroutines.flow.flow {
                emit(com.libtermux.bootstrap.InstallState.Checking)
                if (provider.isAvailable()) {
                    emit(com.libtermux.bootstrap.InstallState.Completed)
                } else {
                    emit(
                        com.libtermux.bootstrap.InstallState.Failed(
                            "No bootstrap binaries found under nativeLibraryDir. " +
                            "Add a bootstrap-<abi> dependency matching this device's ABI " +
                            "(e.g. implementation(\"com.libtermux:bootstrap-arm64:...\"))."
                        )
                    )
                }
            }
        }
        return installer.install(forceReinstall)
    }

    /**
     * Returns true if the Termux bootstrap is ready to use.
     * With a [TermuxConfig.bootstrapProvider] configured, this is an
     * on-disk check (binaries bundled in the APK) rather than the legacy
     * filesDir marker-file check.
     */
    val isInstalled: Boolean
        get() = config.bootstrapProvider?.isAvailable() ?: vfs.isBootstrapInstalled

    /** Whether the device is rooted (su available and functional) */
    val isRooted: Boolean get() = RootUtils.isRooted

    /**
     * Throws if the bootstrap hasn't been installed yet.
     * Used by [com.libtermux.utils.withBridge] to fail fast before
     * running commands against an uninitialized environment.
     */
    fun ensureReady() {
        check(isInstalled) {
            "LibTermux bootstrap is not installed. Call install() and collect it to completion first."
        }
    }

    /** Release resources. Call from onDestroy if needed. */
    fun release() {
        TermuxLogger.d("LibTermux released")
    }

    companion object {
        @Volatile private var instance: LibTermux? = null

        /**
         * Initialize LibTermux. Thread-safe singleton.
         *
         * @param context Application context
         * @param config  Use [termuxConfig] DSL or [TermuxConfig.builder]
         */
        fun init(
            context: Context,
            config: TermuxConfig = TermuxConfig.default(),
        ): LibTermux =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext, config)
                    .also { instance = it }
            }

        fun getInstance(): LibTermux =
            instance ?: throw IllegalStateException(
                "LibTermux not initialized. Call LibTermux.init(context) first."
            )

        fun reset() { instance = null }

        private fun create(context: Context, config: TermuxConfig): LibTermux {
            TermuxLogger.level = config.logLevel
            TermuxLogger.i("LibTermux initializing — version 1.0.0")
            TermuxLogger.i("Architecture: ${config.architecture.resolve().termuxName}")
            TermuxLogger.i("Root available: ${RootUtils.isRooted}")
            val vfs = VirtualFileSystem(context, config)
            return LibTermux(context, config, vfs)
        }
    }
}
