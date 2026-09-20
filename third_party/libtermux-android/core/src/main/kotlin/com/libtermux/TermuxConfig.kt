package com.libtermux

/**
 * Configuration for LibTermux.
 * Use the [termuxConfig] DSL or [TermuxConfig.builder] to create instances.
 *
 * For OS/distro module configuration, use [LibTermux.createOs] from the :os module.
 */
data class TermuxConfig(
    val architecture: Architecture                = Architecture.AUTO,
    val autoInstall: Boolean                      = true,
    val bootstrapVersion: String                  = LATEST_BOOTSTRAP,
    val logLevel: LogLevel                        = LogLevel.INFO,
    val maxCommandTimeoutMs: Long                 = 30_000L,
    val environmentVariables: Map<String, String> = emptyMap(),
    val customBootstrapUrl: String?               = null,
    val enablePackageManager: Boolean             = true,
    val backgroundExecutionEnabled: Boolean       = true,
    val notificationChannelId: String             = "libtermux_bg",
    val notificationChannelName: String           = "LibTermux Service",
    val notificationTitle: String                 = "LibTermux Running",
    val enableLogging: Boolean                    = true,
    /**
     * Resolves bootstrap binaries (bash, apt, proot, ...) from install-time
     * executable storage instead of downloading them into app-private
     * storage at runtime.
     *
     * On Android 10+ (API 29+), a file this app writes into its own
     * `filesDir` can never be executed afterwards — see
     * [com.libtermux.bootstrap.BootstrapProvider] for why. Leaving this
     * null preserves the legacy runtime-download behavior, which will fail
     * with a permission-denied error on API 29+ devices. Set this (via a
     * `bootstrap-<abi>` artifact) to fix that.
     */
    val bootstrapProvider: com.libtermux.bootstrap.BootstrapProvider? = null,
) {
    companion object {
        const val LATEST_BOOTSTRAP = "LATEST"

        private const val BASE_DOWNLOAD_URL =
            "https://github.com/termux/termux-packages/releases/download"
        private const val LATEST_FALLBACK_TAG =
            "bootstrap-2026.05.24-r1+apt.android-7"

        @JvmStatic fun default(): TermuxConfig = TermuxConfig()
        @JvmStatic fun builder(): Builder = Builder()

        /**
         * Build a bootstrap download URL from an arch and version string.
         * When version is [LATEST_BOOTSTRAP] a stable fallback tag is used
         * so this function remains network-free.
         */
        @JvmStatic
        fun bootstrapUrl(arch: Architecture, version: String): String {
            val tag = when {
                version == LATEST_BOOTSTRAP -> LATEST_FALLBACK_TAG
                version.contains("apt")     -> "bootstrap-$version"
                else                        -> "bootstrap-$version+apt.android-7"
            }
            return "$BASE_DOWNLOAD_URL/${tag.replace("+", "%2B")}/bootstrap-${arch.termuxName}.zip"
        }
    }

    class Builder {
        private var cfg = TermuxConfig()
        fun architecture(v: Architecture)       = apply { cfg = cfg.copy(architecture = v)          }
        fun autoInstall(v: Boolean)             = apply { cfg = cfg.copy(autoInstall = v)            }
        fun bootstrapVersion(v: String)         = apply { cfg = cfg.copy(bootstrapVersion = v)       }
        fun logLevel(v: LogLevel)               = apply { cfg = cfg.copy(logLevel = v)               }
        fun maxCommandTimeoutMs(v: Long)        = apply { cfg = cfg.copy(maxCommandTimeoutMs = v)    }
        fun customBootstrapUrl(v: String)       = apply { cfg = cfg.copy(customBootstrapUrl = v)     }
        fun bootstrapProvider(v: com.libtermux.bootstrap.BootstrapProvider) =
            apply { cfg = cfg.copy(bootstrapProvider = v) }
        fun addEnv(key: String, value: String)  = apply {
            cfg = cfg.copy(environmentVariables = cfg.environmentVariables + (key to value))
        }
        fun build(): TermuxConfig = cfg
    }
}

fun termuxConfig(block: TermuxConfigDsl.() -> Unit): TermuxConfig =
    TermuxConfigDsl().apply(block).build()

@DslMarker annotation class TermuxDsl

@TermuxDsl
class TermuxConfigDsl {
    var architecture: Architecture          = Architecture.AUTO
    var autoInstall: Boolean                = true
    var bootstrapVersion: String            = TermuxConfig.LATEST_BOOTSTRAP
    var logLevel: LogLevel                  = LogLevel.INFO
    var maxCommandTimeoutMs: Long           = 30_000L
    var customBootstrapUrl: String?         = null
    var bootstrapProvider: com.libtermux.bootstrap.BootstrapProvider? = null
    var enablePackageManager: Boolean       = true
    var backgroundExecutionEnabled: Boolean = true

    private val envVars = mutableMapOf<String, String>()
    fun env(key: String, value: String) { envVars[key] = value }

    internal fun build() = TermuxConfig(
        architecture               = architecture,
        autoInstall                = autoInstall,
        bootstrapVersion           = bootstrapVersion,
        logLevel                   = logLevel,
        maxCommandTimeoutMs        = maxCommandTimeoutMs,
        environmentVariables       = envVars.toMap(),
        customBootstrapUrl         = customBootstrapUrl,
        bootstrapProvider          = bootstrapProvider,
        enablePackageManager       = enablePackageManager,
        backgroundExecutionEnabled = backgroundExecutionEnabled,
    )
}

enum class Architecture(val termuxName: String) {
    ARM64("aarch64"),
    X86_64("x86_64"),
    ARM("arm"),
    X86("i686"),
    AUTO("") {
        override fun resolve(): Architecture = when (
            android.os.Build.SUPPORTED_ABIS.firstOrNull()
        ) {
            "arm64-v8a"   -> ARM64
            "x86_64"      -> X86_64
            "armeabi-v7a" -> ARM
            "x86"         -> X86
            else          -> ARM64
        }
    };
    open fun resolve(): Architecture = this
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, NONE }
