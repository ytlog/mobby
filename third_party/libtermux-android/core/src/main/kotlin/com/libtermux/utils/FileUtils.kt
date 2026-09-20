/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.utils

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object FileUtils {

    /**
     * 0755 permission bits: rwxr-xr-x
     * Owner: read+write+execute | Group: read+execute | Others: read+execute
     */
    private val MODE_755 =
        OsConstants.S_IRWXU or          // 0700 — owner rwx
        OsConstants.S_IRGRP or          // 0040 — group r
        OsConstants.S_IXGRP or          // 0010 — group x
        OsConstants.S_IROTH or          // 0004 — others r
        OsConstants.S_IXOTH             // 0001 — others x

    /**
     * Apply 0755 to a single file via Os.chmod() (direct POSIX syscall).
     * Follows symlinks — chmodds the symlink target.
     * Unlike Runtime.exec("chmod"), this never deadlocks and needs no PATH.
     */
    fun chmodExecutable(file: File) {
        runCatching { Os.chmod(file.absolutePath, MODE_755) }
    }

    /**
     * Extract a zip stream to a destination directory.
     * Guards against zip-slip attacks by canonicalizing paths.
     *
     * FIX 1 (previous): suspend fun + suspend onProgress so emit() works in flow {}.
     * FIX 2 (this PR):  Os.chmod(MODE_755) applied to every extracted file immediately,
     *   so binaries are executable before makeExecutable() runs. Using Os.chmod()
     *   (direct syscall) instead of File.setExecutable() which silently fails on symlinks.
     */
    suspend fun extractZip(
        zipStream: InputStream,
        destDir: File,
        onProgress: (suspend (Float) -> Unit)? = null,
        totalBytes: Long = -1L,
    ) {
        var extracted = 0L
        val canonicalDest = destDir.canonicalFile

        ZipInputStream(zipStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name).canonicalFile

                // Zip-slip protection
                if (!outFile.path.startsWith(canonicalDest.path + File.separator) &&
                    outFile.path != canonicalDest.path
                ) {
                    TermuxLogger.w("Skipping malicious zip entry: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    // Set 0755 immediately after writing — direct POSIX syscall,
                    // no PATH dependency, no deadlock risk.
                    chmodExecutable(outFile)

                    val entrySize = if (entry.size > 0) entry.size
                                    else entry.compressedSize.coerceAtLeast(0)
                    extracted += entrySize
                    if (totalBytes > 0) onProgress?.invoke(extracted.toFloat() / totalBytes)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Process Termux SYMLINKS.txt and create symlinks.
     */
    fun processSymlinks(symlinksFile: File, baseDir: File) {
        if (!symlinksFile.exists()) {
            TermuxLogger.d("No SYMLINKS.txt found at ${symlinksFile.absolutePath}")
            return
        }

        var processed = 0
        var failed = 0

        symlinksFile.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine

            val parts = when {
                line.contains("←") -> {
                    val split = line.split("←").map { it.trim() }
                    if (split.size == 2) split else null
                }
                line.contains("→") -> {
                    val split = line.split("→").map { it.trim() }
                    if (split.size == 2) listOf(split[1], split[0]) else null
                }
                else -> {
                    val split = line.trim().split(Regex("[ \t]+"))
                    if (split.size >= 2) listOf(split[1], split[0]) else null
                }
            }

            if (parts == null || parts.size != 2) {
                TermuxLogger.w("Cannot parse symlink line: $line")
                failed++
                return@forEachLine
            }

            val target   = parts[0]
            val linkPath = File(baseDir, parts[1])

            linkPath.parentFile?.mkdirs()
            runCatching { linkPath.delete() }

            val proc = Runtime.getRuntime()
                .exec(arrayOf("ln", "-sf", target, linkPath.absolutePath))
            proc.inputStream.readBytes()  // drain — prevent buffer deadlock
            proc.errorStream.readBytes()
            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)

            if (exitCode == 0) {
                processed++
            } else {
                TermuxLogger.w("Failed to create symlink: $linkPath -> $target (exit=$exitCode)")
                failed++
            }
        }

        TermuxLogger.i("Symlinks processed: $processed OK, $failed failed")
    }

    /**
     * Set 0755 (rwxr-xr-x) recursively on every file under [dir].
     *
     * FIX: Previous approach used Runtime.exec("chmod -R 755") which:
     *   (a) Deadlocks if chmod writes to stderr and streams aren't drained.
     *   (b) PATH may not resolve "chmod" in a Java Runtime.exec() context.
     *   (c) File.setExecutable() silently fails on symlinks.
     *
     * New approach: Os.chmod() is a direct POSIX chmod(2) syscall — no
     * subprocess, no PATH, no deadlock risk. It follows symlinks (chmodds
     * the target), so busybox and all its symlinked applets get 0755.
     */
    fun makeExecutable(dir: File) {
        dir.walkTopDown().forEach { file ->
            runCatching { Os.chmod(file.absolutePath, MODE_755) }
        }
        TermuxLogger.d("makeExecutable: chmod 0755 applied to ${dir.absolutePath}")
    }

    /** Compute SHA-256 checksum of a file */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copy stream with progress callback */
    fun InputStream.copyToWithProgress(
        out: OutputStream,
        total: Long,
        onProgress: (Float) -> Unit,
        bufferSize: Int = 8 * 1024,
    ): Long {
        var bytesCopied = 0L
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            if (total > 0) onProgress(bytesCopied.toFloat() / total)
            bytes = read(buffer)
        }
        return bytesCopied
    }

    /** Delete directory recursively, ignoring errors */
    fun File.deleteRecursivelyQuiet(): Boolean =
        runCatching { deleteRecursively() }.getOrDefault(false)
}
