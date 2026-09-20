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

import com.libtermux.LibTermux
import com.libtermux.bridge.TermuxBridge
import com.libtermux.executor.ExecutionResult

// ── Suspend DSL extensions ────────────────────────────────────────────────

/**
 * Run a block with bridge access after ensuring LibTermux is ready.
 * ```kotlin
 * termux.withBridge {
 *     val result = run("ls -la")
 *     println(result.stdout)
 * }
 * ```
 */
suspend fun LibTermux.withBridge(block: suspend TermuxBridge.() -> Unit) {
    ensureReady()
    bridge.block()
}

/**
 * Run command and pipe result to next command (like shell pipe).
 */
suspend fun TermuxBridge.pipe(vararg commands: String): ExecutionResult {
    return run(commands.joinToString(" | "))
}

/**
 * Run command and get stdout lines as List<String>.
 */
suspend fun TermuxBridge.lines(command: String): List<String> =
    run(command).stdoutLines()

/**
 * Run command silently — returns true if exit code is 0.
 */
suspend fun TermuxBridge.check(command: String): Boolean =
    run(command).isSuccess

/**
 * Install packages only if the binary is not already available.
 */
suspend fun TermuxBridge.ensureInstalled(vararg packages: String) {
    packages.forEach { pkg ->
        if (!hasCommand(pkg)) install(pkg)
    }
}

// ── Formatting ────────────────────────────────────────────────────────────

fun ExecutionResult.prettyPrint(): String = buildString {
    appendLine("═══════════════════════════════════════")
    appendLine("CMD:  $command")
    appendLine("EXIT: $exitCode  TIME: ${executionTimeMs}ms")
    if (stdout.isNotEmpty()) { appendLine("STDOUT:"); appendLine(stdout) }
    if (stderr.isNotEmpty()) { appendLine("STDERR:"); appendLine(stderr) }
    appendLine("═══════════════════════════════════════")
}
