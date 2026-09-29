// Modified for mobby (2026-09-29): validate executable bootstrap resources and Android installation paths.
/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.libtermux.bootstrap

import java.io.File

/**
 * Resolves executable binaries that ship in the Termux bootstrap archive
 * (bash, apt, dpkg, tar from the Termux bootstrap archive; proot from a
 * separate source — see build-logic's FetchProotBinaryTask) to a location
 * the OS will actually allow this process to `exec()`.
 *
 * ## Why this exists
 * Since Android 10 (API 29), a file the app itself *wrote* into its own
 * private storage (`filesDir`) can never be executed afterwards, no matter
 * what permission bits are set on it (W^X enforcement) — see
 * [BootstrapInstaller] history for the failure this replaces.
 *
 * The only files Android will let an app exec are ones extracted by
 * PackageManager at *install* time — i.e. native libraries under
 * `ApplicationInfo.nativeLibraryDir`. A [BootstrapProvider] implementation
 * is expected to resolve binary names against files placed there (typically
 * bundled by a `bootstrap-<abi>` Maven artifact as renamed `.so` files).
 *
 * This interface is deliberately not Termux-specific in naming: both
 * `:core` (bash, apt, dpkg — real files in the bootstrap archive) and
 * `:os` (tar — also from the bootstrap archive; proot — fetched
 * separately, see FetchProotBinaryTask) resolve binaries through the same
 * provider, so there is exactly one place that understands "how do I find
 * an executable binary" in this whole project.
 */
interface BootstrapProvider {

    /**
     * Resolve [name] (e.g. "bash", "proot", "tar") to an executable [File].
     * Implementations should return the [nativeLibraryDir]-backed path.
     *
     * @throws NoSuchElementException if this provider doesn't bundle [name].
     */
    fun binaryPath(name: String): File

    /** True if [name] is bundled by this provider — check before calling [binaryPath]. */
    fun hasBinary(name: String): Boolean

    /**
     * True if this provider's binaries are present and ready to use.
     * Since binaries ship inside the APK, this is a cheap on-disk check —
     * never a network call, unlike the old runtime bootstrap download.
     */
    fun isAvailable(): Boolean
}

/**
 * Default [BootstrapProvider] backed by `ApplicationInfo.nativeLibraryDir`.
 *
 * A `bootstrap-<abi>` module bundles binaries as `lib<name>.so` under
 * `src/main/jniLibs/<abi>/` — the standard Termux packaging trick, since
 * that is the one location PackageManager extracts with execute permission
 * intact. This class reverses that naming (`bash` -> `libbash.so`) to
 * locate them at runtime.
 */
class NativeLibBootstrapProvider(
    private val nativeLibraryDir: File,
) : BootstrapProvider {

    private fun soFile(name: String): File = File(nativeLibraryDir, "lib$name.so")

    override fun binaryPath(name: String): File {
        val file = soFile(name)
        if (!file.exists()) {
            throw NoSuchElementException(
                "Binary '$name' not found at ${file.absolutePath}. " +
                "Did you add a bootstrap-<abi> dependency matching this device's ABI?"
            )
        }
        return file
    }

    override fun hasBinary(name: String): Boolean = soFile(name).exists()

    override fun isAvailable(): Boolean =
        soFile("bash").canExecute()

    companion object {
        /**
         * Build a [NativeLibBootstrapProvider] from an Android [Context].
         *
         * ```kotlin
         * val libtermux = LibTermux.init(context, termuxConfig {
         *     bootstrapProvider = NativeLibBootstrapProvider.from(context)
         * })
         * ```
         *
         * Requires a `bootstrap-<abi>` dependency (e.g.
         * `com.libtermux:bootstrap-arm64`) matching this device's ABI to
         * actually contain any binaries — this call always succeeds, but
         * [isAvailable] / [hasBinary] will be false without one.
         */
        fun from(context: android.content.Context): NativeLibBootstrapProvider =
            NativeLibBootstrapProvider(File(context.applicationInfo.nativeLibraryDir))
    }
}
