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
package com.libtermux.bridge

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.executor.OutputLine
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.pkg.PackageManager
import com.libtermux.pkg.PkgResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * High-level API bridge between Kotlin/Java application code
 * and the embedded Linux environment.
 */
class TermuxBridge internal constructor(
    @PublishedApi internal val executor: CommandExecutor,
    private val pkgManager: PackageManager,
    val vfs: VirtualFileSystem,
) {

    // ── Shell Commands ────────────────────────────────────────────────────

    suspend fun run(
        command: String,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.execute(command, workDir, env)

    suspend fun runOrThrow(command: String): String {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return result.stdout
    }

    fun runStreaming(
        command: String,
        workDir: File? = null,
    ): Flow<OutputLine> = executor.executeStreaming(command, workDir)

    fun runStreamingText(command: String): Flow<String> =
        runStreaming(command).map { line ->
            when (line) {
                is OutputLine.Stdout -> line.text
                is OutputLine.Stderr -> "[ERR] ${line.text}"
                is OutputLine.Exit   -> "[Exit: ${line.code}]"
            }
        }

    // ── JSON Support ──────────────────────────────────────────────────────

    suspend fun runJson(command: String): JsonElement {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return Json.parseToJsonElement(result.stdout.trim())
    }

    suspend inline fun <reified T> runAs(command: String): T {
        val result = executor.execute(command)
        if (result.isFailure) throw TermuxCommandException(command, result)
        return Json.decodeFromString(result.stdout.trim())
    }

    // ── Language Runners ──────────────────────────────────────────────────

    suspend fun python(
        script: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.executePython(script, args, env)

    suspend fun node(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult = executor.executeNode(script, env)

    suspend fun bash(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        return executor.execute(script, extraEnv = env)
    }

    /**
     * Execute a Ruby script string.
     * Escapes single quotes using the standard shell technique: '\''
     */
    suspend fun ruby(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        // FIX: shell escape '"'"' must have inner double-quotes backslash-escaped in Kotlin string
        val escaped = script.replace("'", "'\"'\"'")
        return run("ruby -e '$escaped'", env = env)
    }

    /**
     * Execute a Perl script string.
     * Escapes single quotes using the standard shell technique.
     */
    suspend fun perl(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        // FIX: shell escape '"'"' must have inner double-quotes backslash-escaped in Kotlin string
        val escaped = script.replace("'", "'\"'\"'")
        return run("perl -e '$escaped'", env = env)
    }

    /**
     * Execute a PHP script string.
     * Escapes single quotes using the standard shell technique.
     */
    suspend fun php(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        // FIX: shell escape '"'"' must have inner double-quotes backslash-escaped in Kotlin string
        val escaped = script.replace("'", "'\"'\"'")
        return run("php -r '$escaped'", env = env)
    }

    // ── File Operations ───────────────────────────────────────────────────

    fun writeFile(path: String, content: String): File =
        vfs.writeHomeFile(path, content)

    fun writeScript(name: String, content: String): File =
        vfs.writeScript(name, content)

    fun readFile(path: String): String? =
        runCatching { File(vfs.homeDir, path).readText() }.getOrNull()

    fun fileExists(path: String): Boolean =
        File(vfs.homeDir, path).exists()

    // ── Package Management ────────────────────────────────────────────────

    suspend fun install(vararg packages: String): PkgResult =
        pkgManager.install(*packages)

    suspend fun uninstall(vararg packages: String): PkgResult =
        pkgManager.uninstall(*packages)

    suspend fun upgradeAll(): PkgResult =
        pkgManager.upgradeAll()

    suspend fun updateRepo(): PkgResult =
        pkgManager.updateRepo()

    suspend fun pipInstall(vararg packages: String): PkgResult =
        pkgManager.pipInstall(*packages)

    suspend fun npmInstall(vararg packages: String): PkgResult =
        pkgManager.npmInstall(*packages)

    suspend fun gemInstall(vararg packages: String): PkgResult {
        val result = run("gem install ${packages.joinToString(" ")}")
        return if (result.isSuccess) PkgResult.Success(result.stdout) else PkgResult.Failed(result.stderr, result.exitCode)
    }

    // ── Utilities ────────────────────────────────────────────────────────

    suspend fun hasCommand(command: String): Boolean =
        executor.hasBinary(command)

    suspend fun getPath(): String =
        executor.execute("echo $" + "PATH").stdout.trim()

    fun getDiskUsage(): String = vfs.diskUsageString()

    fun getEnvironment(): Map<String, String> = vfs.buildEnv()

    suspend fun getKernelInfo(): String =
        run("uname -a").stdout.trim()
}

class TermuxCommandException(
    val command: String,
    val result: ExecutionResult,
) : Exception("Command '$command' failed with exit ${result.exitCode}: ${result.stderr}")
