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

/**
 * JNI bindings to native helpers (PTY, process forking, symlinks, permissions).
 * Loaded on first use.
 */
object NativeUtils {

    private var loaded = false

    fun load() {
        if (!loaded) {
            runCatching { System.loadLibrary("libtermux_jni") }
                .onFailure { TermuxLogger.e("Failed to load native lib", it) }
            loaded = true
        }
    }

    // Permissions
    @JvmStatic external fun setExecutableRecursive(path: String): Int
    @JvmStatic external fun fileExists(path: String): Boolean

    // Symlinks
    @JvmStatic external fun createSymlink(target: String, linkPath: String): Int
    @JvmStatic external fun readSymlink(path: String): String?

    // Architecture
    @JvmStatic external fun getNativeArch(): String

    // PTY
    @JvmStatic external fun openPty(): IntArray?
    @JvmStatic external fun resizePty(masterFd: Int, cols: Int, rows: Int): Int
    @JvmStatic external fun closePty(fd: Int)

    // Process
    @JvmStatic external fun forkProcess(
        exec: String,
        args: Array<String>,
        env: Array<String>,
        cwd: String?,
        stdinFd: Int,
        stdoutFd: Int,
        stderrFd: Int,
    ): Int
    @JvmStatic external fun sendSignal(pid: Int, signal: Int): Int
    @JvmStatic external fun waitForProcess(pid: Int): Int
}
