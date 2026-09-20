package com.libtermux.os.chroot

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.os.OsConfig
import com.libtermux.os.RootUtils
import com.libtermux.os.SuResult
import com.libtermux.os.distro.Distro
import com.libtermux.os.distro.DistroSetupState
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class ChrootRunner(
    private val vfs:          VirtualFileSystem,
    private val executor:     CommandExecutor,
    private val config:       OsConfig,
    private val mountManager: MountManager = MountManager(),
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(config.downloadTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val distroBaseDir: File
        get() = config.distroStorageDir ?: File(vfs.filesDir, "libtermux/distros")

    fun rootfsDir(distro: Distro)          = File(distroBaseDir, "${distro.id}/rootfs")
    private fun markerFile(distro: Distro) = File(distroBaseDir, "${distro.id}/.installed")
    fun isInstalled(distro: Distro)        = markerFile(distro).exists()

    // ── Setup ─────────────────────────────────────────────────────────────

    fun setup(distro: Distro, forceReinstall: Boolean = false): Flow<DistroSetupState> = flow {
        emit(DistroSetupState.Checking)

        if (!RootUtils.isRooted) {
            emit(DistroSetupState.Failed(distro, "Real chroot requires root access"))
            return@flow
        }

        if (!forceReinstall && isInstalled(distro)) {
            emit(DistroSetupState.AlreadyInstalled(distro))
            return@flow
        }

        try {
            val rootfs  = rootfsDir(distro).also { it.mkdirs() }
            markerFile(distro).delete()

            val tarFile = File(distroBaseDir, "${distro.id}/rootfs${distro.compression.extension}")

            // FIX: downloadRootfs is now suspend so it can call the
            // suspend onProgress lambda inside the download loop.
            // Previously it was a regular fun and onProgress was NEVER called
            // — progress was stuck at 0% the whole download.
            downloadRootfs(distro, tarFile) { downloaded, total, progress ->
                emit(DistroSetupState.Downloading(distro, downloaded, total, progress))
            }

            if (distro.sha256Url != null) {
                emit(DistroSetupState.VerifyingChecksum(distro))
                verifyChecksum(distro, tarFile)
            }

            emit(DistroSetupState.Extracting(distro, 0f))
            extractRootfsAsRoot(tarFile, rootfs)
            tarFile.delete()
            emit(DistroSetupState.Extracting(distro, 1f))

            if (config.configureDns) {
                emit(DistroSetupState.ConfiguringDns(distro))
                configureDns(rootfs)
            }

            if (config.runSetupCommands && distro.setupCommands.isNotEmpty()) {
                mountManager.mountAll(rootfs, config.bindSdCard)
                try {
                    distro.setupCommands.forEach { cmd ->
                        emit(DistroSetupState.ConfiguringDistro(distro, cmd))
                        execute(distro, cmd)
                    }
                } finally {
                    mountManager.unmountAll(rootfs)
                }
            }

            markerFile(distro).writeText(System.currentTimeMillis().toString())
            emit(DistroSetupState.Completed(distro))

        } catch (e: Exception) {
            TermuxLogger.e("ChrootRunner setup failed: ${distro.id}", e)
            emit(DistroSetupState.Failed(distro, e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    // ── Execution ─────────────────────────────────────────────────────────

    suspend fun execute(
        distro:  Distro,
        command: String,
        workDir: String = "/root",
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val rootfs     = rootfsDir(distro).absolutePath
        val chrootCmd  = buildChrootCommand(rootfs, distro.defaultShell, command, workDir)

        when (val result = RootUtils.execute(chrootCmd)) {
            is SuResult.Success -> ExecutionResult(
                stdout          = result.stdout,
                stderr          = result.stderr,
                exitCode        = result.exitCode,
                executionTimeMs = 0L,
                command         = command,
            )
            is SuResult.NoRoot  -> ExecutionResult(
                stdout = "", stderr = "Root not available",
                exitCode = -1, executionTimeMs = 0L, command = command,
            )
            is SuResult.Failed  -> ExecutionResult(
                stdout = "", stderr = result.reason,
                exitCode = -1, executionTimeMs = 0L, command = command,
            )
        }
    }

    suspend fun login(distro: Distro): Process = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir(distro).absolutePath
        mountManager.mountAll(rootfsDir(distro), config.bindSdCard)
        RootUtils.executeSuRaw("chroot $rootfs ${distro.defaultShell} --login")
    }

    fun uninstall(distro: Distro) {
        File(distroBaseDir, distro.id).deleteRecursively()
        TermuxLogger.i("Distro uninstalled (chroot): ${distro.id}")
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun buildChrootCommand(
        rootfs:  String,
        shell:   String,
        command: String,
        workDir: String,
    ): String {
        val env = "HOME=/root TERM=xterm-256color LANG=C.UTF-8 " +
                  "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        return "chroot $rootfs /usr/bin/env -i $env $shell -c ${shellQuote(command)}"
    }

    /**
     * Download rootfs archive emitting real-time progress.
     *
     * FIX: `suspend fun` — allows calling the suspend [onProgress] lambda
     * on every 16 KB chunk. Old `fun` version never called [onProgress].
     */
    private suspend fun downloadRootfs(
        distro:     Distro,
        dest:       File,
        onProgress: suspend (Long, Long, Float) -> Unit,
    ) {
        val response = http.newCall(Request.Builder().url(distro.rootfsUrl).build()).execute()
        check(response.isSuccessful) { "Download failed HTTP ${response.code}: ${distro.rootfsUrl}" }
        val body  = checkNotNull(response.body) { "Empty response body" }
        val total = body.contentLength()
        var downloaded = 0L

        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    downloaded += read
                    // ✅ called on every chunk — real progress now works
                    onProgress(downloaded, total, if (total > 0) downloaded.toFloat() / total else 0f)
                }
            }
        }
        TermuxLogger.i("Downloaded ${distro.displayName}: ${downloaded / 1_048_576} MB")
    }

    private fun verifyChecksum(distro: Distro, tarFile: File) {
        val sha256Url = distro.sha256Url ?: return
        val body = http.newCall(Request.Builder().url(sha256Url).build()).execute().body?.string() ?: return
        val expected = body.lines()
            .firstOrNull { it.contains(tarFile.name) || (it.trim().length == 64 && !it.contains(" ")) }
            ?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase() ?: return
        val actual = sha256(tarFile)
        check(actual == expected) {
            tarFile.delete()
            "SHA-256 mismatch ${distro.displayName}: expected=$expected actual=$actual"
        }
        TermuxLogger.i("Checksum OK: ${distro.displayName}")
    }

    /**
     * Extract rootfs as root to preserve setuid bits and file ownership.
     */
    private suspend fun extractRootfsAsRoot(tarFile: File, destDir: File) {
        val flag = when {
            tarFile.name.endsWith(".tar.gz")  -> "xzf"
            tarFile.name.endsWith(".tar.xz")  -> "xJf"
            tarFile.name.endsWith(".tar.zst") -> "--zstd -xf"
            else -> "xf"
        }
        val result = RootUtils.execute(
            "tar $flag ${shellQuote(tarFile.absolutePath)} " +
            "-C ${shellQuote(destDir.absolutePath)} " +
            "--preserve-permissions --numeric-owner 2>&1"
        )
        if (result is SuResult.Success && !result.isSuccess) {
            throw IllegalStateException("Extraction failed: ${result.stderr}")
        }
    }

    private fun configureDns(rootfsDir: File) =
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver ${config.dnsServer}\n")
        }

    private fun sha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp -> val b = ByteArray(8192); var r: Int
            while (inp.read(b).also { r = it } != -1) d.update(b, 0, r) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shellQuote(s: String) = "'${s.replace("'", "'\"'\"'")}'"
}
