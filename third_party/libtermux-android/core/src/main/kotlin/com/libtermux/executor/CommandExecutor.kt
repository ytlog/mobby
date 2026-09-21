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
package com.libtermux.executor

import com.libtermux.TermuxConfig
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.utils.FileUtils.chmodExecutable
import com.libtermux.utils.FileUtils.makeExecutable
import com.libtermux.utils.NativeUtils
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

/**
 * Core process executor that runs commands inside the VFS Linux environment.
 *
 * Supports both standard ProcessBuilder execution and native PTY-backed
 * interactive execution (via JNI) for full terminal emulation.
 */
class CommandExecutor(
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {

    /**
     * Execute a command and return a complete [ExecutionResult].
     *
     * @param command   Shell command string (e.g. "python3 -c 'print(1+1)'")
     * @param workDir   Working directory (defaults to HOME)
     * @param extraEnv  Extra environment variables merged on top of VFS env
     * @param shell     Shell binary name in PREFIX/bin (default: bash)
     */
    suspend fun execute(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult {
        val start = System.currentTimeMillis()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exit = -1
        executeStreaming(command, workDir, extraEnv, shell).collect { line ->
            when (line) {
                is OutputLine.Stdout -> if (stdout.length < 1048576) stdout.appendLine(line.text)
                is OutputLine.Stderr -> if (stderr.length < 1048576) stderr.appendLine(line.text)
                is OutputLine.Exit -> exit = line.code
            }
        }
        return ExecutionResult(stdout.toString().trimEnd(), stderr.toString().trimEnd(), exit, System.currentTimeMillis() - start, command)
    }

    fun executeArgsStreaming(
        args: List<String>,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        onStarted: (Int) -> Unit = {},
        onTerminated: (Int?) -> Unit = {},
        input: Flow<ByteArray>? = null,
    ): Flow<OutputLine> = PipeProcess.stream(args, workDir ?: vfs.homeDir, vfs.buildEnv(extraEnv), config.maxCommandTimeoutMs, onStarted, onTerminated, input)

    fun executeStreaming(
        command: String,
        workDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): Flow<OutputLine> = executeArgsStreaming(listOf(resolveBinary(shell).absolutePath, "-c", command), workDir, extraEnv)

    /**
     * Execute a Python script string directly.
     */
    suspend fun executePython(
        script: String,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.py")
        scriptFile.writeText(script)
        val argStr = args.joinToString(" ")
        return try {
            execute("python3 ${scriptFile.absolutePath} $argStr".trim(), extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a Node.js script string directly.
     */
    suspend fun executeNode(
        script: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecutionResult {
        val scriptFile = File(vfs.tmpDir, "script_${System.currentTimeMillis()}.js")
        scriptFile.writeText(script)
        return try {
            execute("node ${scriptFile.absolutePath}", extraEnv = extraEnv)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Execute a shell script file.
     */
    suspend fun executeScript(
        scriptFile: File,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        shell: String = "bash",
    ): ExecutionResult {
        scriptFile.setExecutable(true, false)
        val argStr = args.joinToString(" ")
        return execute(
            command  = "${scriptFile.absolutePath} $argStr".trim(),
            extraEnv = extraEnv,
            shell    = shell,
        )
    }

    /**
     * Test if a binary exists in PREFIX/bin.
     */
    suspend fun hasBinary(name: String): Boolean {
        val result = execute("which $name")
        return result.isSuccess && result.stdout.isNotBlank()
    }

    /**
     * Resolve a bootstrap binary name (e.g. "bash", "proot", "tar") to its
     * executable [File] path, using the same resolution order as command
     * execution: [TermuxConfig.bootstrapProvider] first, legacy
     * `vfs.binDir` fallback otherwise.
     *
     * Other modules (e.g. `:os`'s ProotRunner, which needs `proot` and
     * `tar` directly rather than through [execute]) should call this
     * instead of touching `vfs.binDir` themselves, so there is exactly one
     * place that understands binary resolution.
     */
    fun resolveBinary(name: String): File {
        val provider = config.bootstrapProvider
        if (provider != null) return provider.binaryPath(name)
        return File(vfs.binDir, name)
    }

    /** True if [name] is available to execute, via provider or legacy binDir. */
    fun hasBundledBinary(name: String): Boolean {
        val provider = config.bootstrapProvider
        return if (provider != null) provider.hasBinary(name) else File(vfs.binDir, name).exists()
    }

}
