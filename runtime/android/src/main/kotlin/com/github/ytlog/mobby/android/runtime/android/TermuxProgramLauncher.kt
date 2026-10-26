package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.github.ytlog.mobby.android.programs.run.LaunchUnavailable
import com.github.ytlog.mobby.android.programs.run.ProcessSpec
import com.github.ytlog.mobby.android.programs.run.ProgramProcess
import com.github.ytlog.mobby.android.programs.run.ProgramProcessLauncher
import com.libtermux.LibTermux
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Starts program background processes with the app's Node runtime, each in its own process
 * group. Live processes are recorded with their start time so a later app process can end them
 * without touching a reused PID.
 */
class TermuxProgramLauncher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logDir: File,
) : ProgramProcessLauncher {
    private val prefs = context.getSharedPreferences("program-processes", Context.MODE_PRIVATE)

    override fun launch(spec: ProcessSpec): ProgramProcess {
        val sdk = LibTermux.init(context, RuntimeEnvironment.termuxConfig(context))
        if (!sdk.isInstalled) throw LaunchUnavailable("runtime_not_installed")
        val node = File(sdk.vfs.binDir, "node")
        if (!node.canExecute() && !RuntimeEnvironment.repairNativeLinks(context, sdk)) throw LaunchUnavailable("runtime_update_pending")
        if (!node.canExecute()) throw LaunchUnavailable("runtime_not_ready")
        val runner = File(spec.sdkDir, "runner.cjs")
        if (!runner.isFile) throw LaunchUnavailable("sdk_missing")
        spec.workDir.mkdirs()
        logDir.mkdirs()
        val log = ProgramLog(File(logDir, "${spec.runId}.log"))
        val started = CompletableDeferred<Int>()
        val exit = CompletableDeferred<Int?>()
        val env = mapOf(
            "NODE_PATH" to File(spec.sdkDir, "node_modules").absolutePath,
            "MOBBY_API_PORT" to spec.apiPort.toString(),
            "MOBBY_RUN_ID" to spec.runId,
            "MOBBY_ENTRY" to File(spec.packageDir, spec.entry).absolutePath,
        )
        val job = scope.launch(Dispatchers.IO) {
            try {
                sdk.executor.executeArgsStreaming(listOf(node.absolutePath, runner.absolutePath), spec.workDir, env,
                    onStarted = { pid -> remember(pid); started.complete(pid) },
                    onTerminated = { code -> exit.complete(code) },
                    input = flowOf("${spec.credential}\n".toByteArray()),
                    timeoutMs = 0,
                ).collect { line ->
                    when (line) {
                        is OutputLine.Stdout -> log.append("out", line.text)
                        is OutputLine.Stderr -> log.append("err", line.text)
                        is OutputLine.Exit -> Unit
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { log.append("err", "launcher: ${error.message}"); started.completeExceptionally(error) }
            finally { exit.complete(null); started.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { forget(it.getCompleted()) } } }
        }
        val pid = runBlocking { withTimeoutOrNull(5_000) { runCatching { started.await() }.getOrNull() } }
            ?: run { job.cancel(); throw LaunchUnavailable("process_did_not_start") }
        return object : ProgramProcess {
            override val pid = pid
            override suspend fun awaitExit() = exit.await()
            override fun terminate() { job.cancel() }
        }
    }

    override fun recoverOrphans() {
        for ((key, value) in prefs.all) {
            val pid = key.toIntOrNull() ?: continue
            if (startTime(pid) == value) runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
        }
        prefs.edit().clear().apply()
    }

    private fun remember(pid: Int) {
        // JNI keeps the child unreaped until the stream ends, so /proc identity is stable here.
        var identity: String? = null
        repeat(100) { if (identity == null) { identity = startTime(pid); if (identity == null) Thread.sleep(10) } }
        identity?.let { prefs.edit().putString(pid.toString(), it).apply() }
    }

    private fun forget(pid: Int) = prefs.edit().remove(pid.toString()).apply()

    private fun startTime(pid: Int): String? =
        runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19] }.getOrNull()
}

/** Per-run output, bounded so a chatty program cannot fill storage. */
internal class ProgramLog(private val file: File, private val limit: Long = 256 * 1024) {
    private var truncated = false
    @Synchronized fun append(stream: String, text: String) {
        if (truncated) return
        if (file.length() + text.length > limit) { file.appendText("[$stream] …truncated\n"); truncated = true; return }
        file.appendText("[$stream] $text\n")
    }
}
