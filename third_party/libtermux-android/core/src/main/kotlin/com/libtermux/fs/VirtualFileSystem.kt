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
package com.libtermux.fs

import android.content.Context
import com.libtermux.TermuxConfig
import com.libtermux.utils.ArchUtils.resolved
import java.io.File

/**
 * Manages the virtual Linux directory structure inside the app's private storage.
 *
 * Layout mirrors Termux exactly:
 *   filesDir/libtermux/
 *     ├── usr/           ← PREFIX  ($PREFIX)
 *     │   ├── bin/       ← executables (bash, python3, pkg, apt ...)
 *     │   ├── lib/       ← shared libraries
 *     │   ├── libexec/   ← auxiliary executables
 *     │   ├── etc/       ← config files (apt sources, resolv.conf ...)
 *     │   ├── share/     ← data files
 *     │   ├── var/       ← variable data (logs, caches)
 *     │   └── tmp/       ← temporary files (also exposed as $TMPDIR)
 *     ├── home/          ← HOME ($HOME)
 *     └── .bootstrap_ok  ← marker file (bootstrap installed)
 */
class VirtualFileSystem(context: Context, private val config: TermuxConfig) {

    /** Root of the entire virtual filesystem: filesDir/libtermux */
    val root: File = File(context.filesDir, "libtermux").also { it.mkdirs() }

    /** The app's private files directory (context.filesDir) — for locating sibling storage like distros/ */
    val filesDir: File = context.filesDir

    val prefixDir: File   = File(root, "usr").also       { it.mkdirs() }
    val homeDir: File     = File(root, "home").also      { it.mkdirs() }
    val tmpDir: File      = File(prefixDir, "tmp").also  { it.mkdirs() }
    val binDir: File      = File(prefixDir, "bin").also  { it.mkdirs() }
    val libDir: File      = File(prefixDir, "lib").also  { it.mkdirs() }
    val libexecDir: File  = File(prefixDir, "libexec").also { it.mkdirs() }
    val etcDir: File      = File(prefixDir, "etc").also  { it.mkdirs() }
    val shareDir: File    = File(prefixDir, "share").also{ it.mkdirs() }
    val varDir: File      = File(prefixDir, "var").also  { it.mkdirs() }

    private val markerFile: File = File(root, ".bootstrap_ok")

    val isBootstrapInstalled: Boolean get() = markerFile.exists()

    fun markBootstrapInstalled() { markerFile.createNewFile() }
    fun clearBootstrapMarker() { markerFile.delete() }

    fun resetAll() {
        root.deleteRecursively()
        root.mkdirs()
        prefixDir.mkdirs()
        homeDir.mkdirs()
        tmpDir.mkdirs()
    }

    /** Build environment variables map for process execution — mirrors Termux exactly */
    fun buildEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        return buildMap {
            put("HOME",    homeDir.absolutePath)
            put("PREFIX",  prefixDir.absolutePath)
            put("TMPDIR",  tmpDir.absolutePath)
            put("TERM",    "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG",    "en_US.UTF-8")
            put("SHELL",   "${prefixDir.absolutePath}/bin/bash")
            put("PATH",    buildPath())
            put("LD_LIBRARY_PATH", buildLdLibraryPath())
            put("LD_PRELOAD",      findLdPreload())
            put("TERMUX_PREFIX",   prefixDir.absolutePath)
            put("TERMUX_HOME",     homeDir.absolutePath)
            put("TERMUX_VERSION",  "0.118.0") // Compatibility shim
            put("ANDROID_DATA",    "/data")
            put("ANDROID_ROOT",    "/system")
            put("TERMUX_API_VERSION", "0.50.1")
            // Merge custom env vars from config
            putAll(config.environmentVariables)
            putAll(extra)
        }
    }

    private fun buildPath(): String = listOf(
        "${prefixDir.absolutePath}/bin",
        "${prefixDir.absolutePath}/bin/applets",
        "/system/bin",
        "/system/xbin",
    ).joinToString(":")

    private fun buildLdLibraryPath(): String = listOf(
        "${prefixDir.absolutePath}/lib",
        "/system/lib64",
        "/system/lib",
    ).joinToString(":")

    private fun findLdPreload(): String {
        val preloadLib = File(prefixDir, "lib/libtermux-exec.so")
        return if (preloadLib.exists()) preloadLib.absolutePath else ""
    }

    /** Resolve a path relative to PREFIX */
    fun prefixPath(relative: String): File = File(prefixDir, relative)

    /** Resolve a path relative to HOME */
    fun homePath(relative: String): File = File(homeDir, relative)

    /** Write a file into HOME directory */
    fun writeHomeFile(name: String, content: String): File {
        val file = File(homeDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    /** Write a file into PREFIX/bin and make it executable */
    fun writeScript(name: String, content: String): File {
        val file = File(binDir, name)
        file.writeText(content)
        file.setExecutable(true, false)
        return file
    }

    /** Get disk usage of the entire libtermux root in bytes */
    fun diskUsageBytes(): Long = root.walkTopDown().sumOf { it.length() }

    /** Human-readable disk usage */
    fun diskUsageString(): String {
        val bytes = diskUsageBytes()
        return when {
            bytes < 1024             -> "${bytes}B"
            bytes < 1024 * 1024      -> "${"%.1f".format(bytes / 1024.0)}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))}MB"
            else                     -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))}GB"
        }
    }
}
