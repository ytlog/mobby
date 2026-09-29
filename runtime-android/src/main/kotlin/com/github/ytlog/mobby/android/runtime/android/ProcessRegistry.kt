package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import java.io.File

/** Records process identity before observation, preventing PID reuse from targeting unrelated work. */
internal class ProcessRegistry(
    context: Context,
    private val readStartTime: (Int) -> String? = { pid ->
        runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19] }.getOrNull()
    },
    private val pause: () -> Unit = { Thread.sleep(10) },
) {
    private val prefs = context.getSharedPreferences("runtime-process", Context.MODE_PRIVATE)
    private fun startTime(pid: Int): String? = readStartTime(pid)
    fun started(pid: Int) {
        // spawn returns immediately after fork. Android may briefly hide /proc until
        // the child finishes launch. JNI retains the child unreaped during this
        // callback, so its PID cannot be reused while we wait for its identity.
        var identity = startTime(pid)
        repeat(100) {
            if (identity == null) {
                pause()
                identity = startTime(pid)
            }
        }
        checkNotNull(identity) { "Cannot identify runtime process" }
        check(prefs.edit().putInt("pid", pid).putString("identity", identity).commit())
    }
    fun terminated(exitCode: Int?) { if (exitCode != null) check(prefs.edit().clear().commit()) }
    suspend fun recover() {
        val pid = prefs.getInt("pid", -1)
        if (pid <= 0) return
        val identity = prefs.getString("identity", null)
        if (startTime(pid) == identity) {
            // Native launcher uses an isolated session/process group with pgid == pid.
            runCatching { Os.kill(-pid, OsConstants.SIGTERM) }
            repeat(20) { if (startTime(pid) == identity) delay(25) }
            if (startTime(pid) == identity) runCatching { Os.kill(-pid, OsConstants.SIGKILL) }
            repeat(40) { if (startTime(pid) == identity) delay(25) }
            check(startTime(pid) != identity) { "Previous runtime process termination could not be confirmed" }
        }
        check(prefs.edit().clear().commit())
    }
}
