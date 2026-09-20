package com.libtermux.os.distro

/**
 * Progress states emitted by [OsEnvironment.setupDistro].
 * Collect these in a Flow to show real-time progress in the UI.
 */
sealed class DistroSetupState {

    /** Checking if distro is already installed */
    object Checking : DistroSetupState()

    /** Distro is already installed — setup skipped */
    data class AlreadyInstalled(val distro: Distro) : DistroSetupState()

    /** Ensuring proot binary is present via pkg */
    object InstallingProot : DistroSetupState()

    /** Downloading the rootfs archive */
    data class Downloading(
        val distro: Distro,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float,          // 0.0 — 1.0
    ) : DistroSetupState()

    /** Verifying SHA-256 checksum */
    data class VerifyingChecksum(val distro: Distro) : DistroSetupState()

    /** Extracting the rootfs archive with tar */
    data class Extracting(
        val distro: Distro,
        val progress: Float,          // 0.0 — 1.0 (estimated from time)
    ) : DistroSetupState()

    /** Running distro-specific post-setup commands (apt update etc.) */
    data class ConfiguringDistro(val distro: Distro, val step: String) : DistroSetupState()

    /** Fixing DNS resolver */
    data class ConfiguringDns(val distro: Distro) : DistroSetupState()

    /** Setup completed successfully */
    data class Completed(val distro: Distro) : DistroSetupState()

    /** Setup failed */
    data class Failed(
        val distro: Distro,
        val reason: String,
        val cause: Throwable? = null,
    ) : DistroSetupState()
}
