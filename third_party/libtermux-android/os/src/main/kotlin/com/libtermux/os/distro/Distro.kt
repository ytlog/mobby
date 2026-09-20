package com.libtermux.os.distro

/**
 * Compression format of the rootfs archive.
 */
enum class CompressionType(val tarFlag: String, val extension: String) {
    GZ("xzf",  ".tar.gz"),
    XZ("xJf",  ".tar.xz"),
    ZSTD("--zstd -xf", ".tar.zst"),
}

/**
 * Supported Linux distros with official ARM64 rootfs sources.
 *
 * Each distro is identified by a stable [id] used as the directory name on disk.
 * [rootfsUrl] always points to an official ARM64 minimal/base image so no
 * third-party mirror trust is required.
 */
sealed class Distro(
    val id: String,
    val displayName: String,
    val rootfsUrl: String,
    val sha256Url: String?,
    val compression: CompressionType,
    val defaultShell: String = "/bin/bash",
    val setupCommands: List<String> = emptyList(),
) {
    // ── Official distros ────────────────────────────────────────────────

    /** Kali Linux — rolling release, ARM64 minimal rootfs */
    object Kali : Distro(
        id           = "kali",
        displayName  = "Kali Linux",
        rootfsUrl    = "https://kali.download/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz",
        sha256Url    = "https://kali.download/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz.sha256sum",
        compression  = CompressionType.XZ,
        defaultShell = "/bin/bash",
        setupCommands = listOf(
            "apt-get update -y",
            "apt-get install -y kali-linux-headless 2>/dev/null || true",
        ),
    )

    /** Ubuntu 24.04 LTS — official ARM64 base */
    object Ubuntu2404 : Distro(
        id           = "ubuntu-24.04",
        displayName  = "Ubuntu 24.04 LTS",
        rootfsUrl    = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz",
        sha256Url    = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
        compression  = CompressionType.GZ,
        defaultShell = "/bin/bash",
        setupCommands = listOf(
            "apt-get update -y",
            "DEBIAN_FRONTEND=noninteractive apt-get install -y sudo curl wget nano 2>/dev/null || true",
        ),
    )

    /** Ubuntu 22.04 LTS — official ARM64 base */
    object Ubuntu2204 : Distro(
        id           = "ubuntu-22.04",
        displayName  = "Ubuntu 22.04 LTS",
        rootfsUrl    = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz",
        sha256Url    = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/SHA256SUMS",
        compression  = CompressionType.GZ,
        defaultShell = "/bin/bash",
    )

    /** Debian 12 Bookworm — proot-distro official ARM64 image */
    object Debian12 : Distro(
        id           = "debian-12",
        displayName  = "Debian 12 Bookworm",
        rootfsUrl    = "https://github.com/termux/proot-distro/releases/download/v4.18.0/debian-aarch64-pd-v4.18.0.tar.xz",
        sha256Url    = null,
        compression  = CompressionType.XZ,
        defaultShell = "/bin/bash",
        setupCommands = listOf("apt-get update -y"),
    )

    /** Alpine Linux 3.19 — ultra-minimal, fastest to download (~5 MB) */
    object Alpine : Distro(
        id           = "alpine",
        displayName  = "Alpine Linux 3.19",
        rootfsUrl    = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
        sha256Url    = "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz.sha256",
        compression  = CompressionType.GZ,
        defaultShell = "/bin/sh",
        setupCommands = listOf("apk update"),
    )

    /** Fedora 40 — official ARM64 base container image */
    object Fedora40 : Distro(
        id           = "fedora-40",
        displayName  = "Fedora 40",
        rootfsUrl    = "https://dl.fedoraproject.org/pub/fedora/linux/releases/40/Container/aarch64/images/Fedora-Container-Base-40-1.14.aarch64.tar.xz",
        sha256Url    = null,
        compression  = CompressionType.XZ,
        defaultShell = "/bin/bash",
        setupCommands = listOf("dnf update -y"),
    )

    /** Custom distro — provide your own rootfs URL */
    data class Custom(
        val customId: String,
        val customName: String,
        val url: String,
        val checksumUrl: String? = null,
        val compressionType: CompressionType = CompressionType.GZ,
        val shell: String = "/bin/bash",
    ) : Distro(
        id           = customId,
        displayName  = customName,
        rootfsUrl    = url,
        sha256Url    = checksumUrl,
        compression  = compressionType,
        defaultShell = shell,
    )

    companion object {
        /** All built-in distros */
        val all: List<Distro> = listOf(
            Kali, Ubuntu2404, Ubuntu2204, Debian12, Alpine, Fedora40,
        )

        /** Find a built-in distro by id */
        fun fromId(id: String): Distro? = all.firstOrNull { it.id == id }
    }
}
