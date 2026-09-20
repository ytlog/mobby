package com.libtermux.os

import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root detection and `su` execution utilities.
 *
 * Root detection is cached after the first call — the result doesn't change
 * at runtime. Call [refresh] to force re-detection if needed.
 */
object RootUtils {

    private val SU_PATHS = listOf(
        "/sbin/su", "/system/bin/su", "/system/xbin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
        "/su/bin/su", "/magisk/.core/bin/su",
    )

    @Volatile private var _rootAvailable: Boolean? = null

    /**
     * Returns true if root (su) is available AND functional.
     * Result is cached — subsequent calls return instantly.
     */
    val isRooted: Boolean
        get() = _rootAvailable ?: checkRoot().also { _rootAvailable = it }

    /** Force re-detection (e.g. after Magisk install) */
    fun refresh() { _rootAvailable = null }

    /** Returns true if any known `su` binary exists on the filesystem */
    fun hasSuBinary(): Boolean = SU_PATHS.any { File(it).exists() } ||
        runCatching {
            Runtime.getRuntime().exec(arrayOf("which", "su"))
                .also { p ->
                    p.inputStream.readBytes()
                    p.errorStream.readBytes()
                }
                .waitFor() == 0
        }.getOrDefault(false)

    /**
     * Test whether su actually grants root by running `id` and checking for uid=0.
     * This is more reliable than just checking binary existence.
     */
    suspend fun testSu(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proc = executeSuRaw("id")
            val output = proc.inputStream.bufferedReader().readText()
            proc.errorStream.readBytes()
            proc.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * Execute a single shell command as root via `su -c`.
     * Returns [SuResult] with stdout, stderr, and exit code.
     */
    suspend fun execute(command: String): SuResult = withContext(Dispatchers.IO) {
        if (!isRooted) return@withContext SuResult.NoRoot

        runCatching {
            val proc = executeSuRaw(command)
            val stdout = proc.inputStream.bufferedReader().readText().trimEnd()
            val stderr = proc.errorStream.bufferedReader().readText().trimEnd()
            val exit   = proc.waitFor()
            SuResult.Success(stdout, stderr, exit)
        }.getOrElse { e ->
            TermuxLogger.e("su execute failed: $command", e)
            SuResult.Failed(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Execute a command as root and return the raw [Process].
     * Caller is responsible for reading streams and calling waitFor().
     */
    fun executeSuRaw(command: String): Process =
        ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()

    // ── Internals ───────────────────────────────────────────────────────

    private fun checkRoot(): Boolean {
        if (!hasSuBinary()) return false
        return runCatching {
            val proc = executeSuRaw("id")
            val out  = proc.inputStream.bufferedReader().readText()
            proc.errorStream.readBytes()
            proc.waitFor()
            out.contains("uid=0").also {
                TermuxLogger.i("Root check: ${if (it) "ROOTED" else "NOT ROOTED"}")
            }
        }.getOrDefault(false)
    }
}

/** Result of a `su` command execution */
sealed class SuResult {
    /** Root not available on this device */
    object NoRoot : SuResult()

    /** Command executed successfully */
    data class Success(
        val stdout:   String,
        val stderr:   String,
        val exitCode: Int,
    ) : SuResult() {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** Command execution threw an exception */
    data class Failed(val reason: String, val cause: Throwable? = null) : SuResult()
}
