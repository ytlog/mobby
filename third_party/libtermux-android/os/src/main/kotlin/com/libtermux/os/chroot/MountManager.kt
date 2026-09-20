package com.libtermux.os.chroot

import com.libtermux.os.RootUtils
import com.libtermux.utils.TermuxLogger
import java.io.File

/**
 * Manages bind mounts required for a real chroot environment.
 *
 * A proper chroot needs /proc, /sys, /dev mounted inside the rootfs
 * so that system tools work correctly. These require root.
 *
 * All operations are executed via `su -c` and are idempotent —
 * safe to call even if already mounted.
 */
internal class MountManager {

    data class MountSpec(
        val source: String,
        val target: String,      // relative to rootfs, e.g. "proc"
        val fsType: String,      // e.g. "proc", "sysfs", "bind"
        val options: String = "rbind",
    )

    private val standardMounts = listOf(
        MountSpec("/proc",           "proc",           "proc",    "rbind"),
        MountSpec("/sys",            "sys",            "sysfs",   "rbind"),
        MountSpec("/dev",            "dev",            "devtmpfs","rbind"),
        MountSpec("/dev/pts",        "dev/pts",        "devpts",  "rbind"),
        MountSpec("/dev/shm",        "dev/shm",        "tmpfs",   "rbind"),
    )

    /**
     * Mount /proc, /sys, /dev (and sub-mounts) inside [rootfsDir].
     * Skips entries that are already mounted.
     */
    suspend fun mountAll(rootfsDir: File, bindSdCard: Boolean = true) {
        standardMounts.forEach { spec ->
            val target = File(rootfsDir, spec.target)
            target.mkdirs()
            mountIfNeeded(spec.source, target.absolutePath)
        }

        if (bindSdCard) {
            val sdcard = File(rootfsDir, "sdcard")
            sdcard.mkdirs()
            mountIfNeeded("/sdcard", sdcard.absolutePath)
        }

        TermuxLogger.i("Bind mounts applied to ${rootfsDir.absolutePath}")
    }

    /**
     * Unmount everything mounted inside [rootfsDir].
     * Uses `umount -l` (lazy) so processes don't need to be killed first.
     */
    suspend fun unmountAll(rootfsDir: File) {
        val targets = buildList {
            add(File(rootfsDir, "dev/pts").absolutePath)
            add(File(rootfsDir, "dev/shm").absolutePath)
            add(File(rootfsDir, "dev").absolutePath)
            add(File(rootfsDir, "sys").absolutePath)
            add(File(rootfsDir, "proc").absolutePath)
            add(File(rootfsDir, "sdcard").absolutePath)
        }

        targets.forEach { target ->
            unmount(target)
        }

        TermuxLogger.i("Bind mounts removed from ${rootfsDir.absolutePath}")
    }

    /**
     * Returns true if [path] is currently listed in /proc/mounts.
     */
    suspend fun isMounted(path: String): Boolean {
        val result = RootUtils.execute("grep -q '${path}' /proc/mounts && echo yes || echo no")
        return result is com.libtermux.os.SuResult.Success &&
               result.stdout.trim() == "yes"
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun mountIfNeeded(source: String, target: String) {
        if (isMounted(target)) {
            TermuxLogger.d("Already mounted: $target — skipping")
            return
        }
        val result = RootUtils.execute("mount --rbind $source $target")
        if (result is com.libtermux.os.SuResult.Success && !result.isSuccess) {
            TermuxLogger.w("mount --rbind $source $target failed (exit=${result.exitCode}): ${result.stderr}")
        } else {
            TermuxLogger.d("Mounted: $source → $target")
        }
    }

    private suspend fun unmount(target: String) {
        if (!isMounted(target)) return
        val result = RootUtils.execute("umount -l $target 2>/dev/null || true")
        TermuxLogger.d("Unmounted (lazy): $target")
    }
}
