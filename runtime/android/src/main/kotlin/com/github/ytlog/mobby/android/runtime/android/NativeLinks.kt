package com.github.ytlog.mobby.android.runtime.android

import java.io.File
import java.nio.file.Files

/**
 * Bundled executables are links into the APK's native library directory, which moves on every app
 * update. Runtime preparation and program launches both relink under one lock.
 */
internal object NativeLinks {
    private val lock = Any()

    fun link(prefix: File, nativeDir: String, mapping: Map<String, String>) = synchronized(lock) {
        for ((relative, library) in mapping) {
            check(!relative.startsWith('/') && relative.split('/').none { it == ".." })
            val target = File(prefix, relative)
            target.parentFile?.mkdirs()
            Files.deleteIfExists(target.toPath())
            Files.createSymbolicLink(target.toPath(), File(nativeDir, library).toPath())
        }
    }

    /** Relinks only when the extracted runtime matches the bundled version; otherwise a full preparation is needed. */
    fun repair(root: File, prefix: File, nativeDir: String, bundledVersion: String, mapping: Map<String, String>): Boolean = synchronized(lock) {
        val marker = File(root, ".mobby-bootstrap")
        if (!marker.isFile || marker.readText() != bundledVersion) return false
        link(prefix, nativeDir, mapping)
        true
    }
}
