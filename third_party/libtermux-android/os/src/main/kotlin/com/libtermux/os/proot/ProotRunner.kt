package com.libtermux.os.proot

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.os.OsConfig
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

internal class ProotRunner(
    private val vfs:      VirtualFileSystem,
    private val executor: CommandExecutor,
    private val config:   OsConfig,
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

        if (!forceReinstall && isInstalled(distro)) {
            emit(DistroSetupState.AlreadyInstalled(distro))
            return@flow
        }

        try {
            emit(DistroSetupState.InstallingProot)
            ensureProot()

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
            extractRootfs(tarFile, rootfs)
            tarFile.delete()
            emit(DistroSetupState.Extracting(distro, 1f))

            if (config.configureDns) {
                emit(DistroSetupState.ConfiguringDns(distro))
                configureDns(rootfs)
            }

            if (config.runSetupCommands) {
                distro.setupCommands.forEach { cmd ->
                    emit(DistroSetupState.ConfiguringDistro(distro, cmd))
                    execute(distro, cmd)
                }
            }

            markerFile(distro).writeText(System.currentTimeMillis().toString())
            emit(DistroSetupState.Completed(distro))

        } catch (e: Exception) {
            TermuxLogger.e("Distro setup failed: ${distro.id}", e)
            emit(DistroSetupState.Failed(distro, e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    // ── Execution ─────────────────────────────────────────────────────────

    suspend fun execute(distro: Distro, command: String, workDir: String = "/root"): ExecutionResult =
        withContext(Dispatchers.IO) { executor.execute(buildProotCommand(distro, command, workDir)) }

    fun login(distro: Distro): Process =
        ProcessBuilder(executor.resolveBinary("bash").absolutePath, "-c", buildProotCommand(distro, null, "/root"))
            .directory(vfs.homeDir)
            .also { pb -> pb.environment().clear(); pb.environment().putAll(vfs.buildEnv()) }
            .redirectErrorStream(false)
            .start()

    fun uninstall(distro: Distro) {
        File(distroBaseDir, distro.id).deleteRecursively()
        TermuxLogger.i("Distro uninstalled: ${distro.id}")
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Verify `proot` is available.
     *
     * `proot` is genuinely NOT part of the Termux bootstrap archive —
     * verified directly by inspecting a real bootstrap-aarch64.zip (no
     * proot binary, no SYMLINKS.txt entry for one), and independently
     * confirmed by termux/proot-distro's own README, which describes
     * `pkg install proot-distro` as pulling in `proot` as a *separate* apt
     * package dependency. (An earlier version of this comment claimed
     * proot ships in the bootstrap archive based on a build-script read
     * that didn't hold up against the actual archive contents — corrected
     * after checking.)
     *
     * The previous implementation called `pkg install -y proot` at
     * runtime, which is broken on API 29+ for the same reason bash/apt/dpkg
     * needed BootstrapProvider: apt writes downloaded packages into
     * app-private storage, which can never be exec'd afterwards.
     *
     * The fix here: a `bootstrap-<abi>` module's `fetchProotBinary` Gradle
     * task (build-logic) downloads a standalone static proot binary at
     * *build* time from a third-party source (see FetchProotBinaryTask's
     * KDoc for that source and its tradeoffs) and places it alongside
     * bash/apt/dpkg/tar under the same nativeLibraryDir — so it resolves
     * through the exact same [executor].resolveBinary("proot") path.
     */
    private fun ensureProot() {
        if (!executor.hasBundledBinary("proot")) {
            throw IllegalStateException(
                "proot binary not found. Make sure your bootstrap-<abi> dependency has " +
                "bootstrapFetch.includeProot = true (the default in the published modules) " +
                "before setting up a distro."
            )
        }
    }

    private fun buildProotCommand(distro: Distro, command: String?, workDir: String): String {
        val rootfs = rootfsDir(distro).absolutePath
        val proot  = executor.resolveBinary("proot").absolutePath
        val env    = "/usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 " +
                     "ANDROID_ROOT=/system " +
                     "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
        val shell  = if (command != null) "${distro.defaultShell} -c ${shellQuote(command)}"
                     else "${distro.defaultShell} --login"
        return buildString {
            append("$proot --link2symlink")
            if (config.fakeRoot) append(" -0")
            append(" -r $rootfs -w $workDir")
            buildMounts(distro).forEach { append(" -b $it") }
            config.extraBindMounts.forEach { append(" -b $it") }
            append(" --kill-on-exit $env$shell")
        }
    }

    private fun buildMounts(distro: Distro) = buildList {
        add("/dev"); add("/dev/urandom:/dev/random")
        add("/proc"); add("/proc/self/fd:/dev/fd")
        add("/proc/self/fd/0:/dev/stdin")
        add("/proc/self/fd/1:/dev/stdout")
        add("/proc/self/fd/2:/dev/stderr")
        add("/sys")
        if (config.bindSdCard && File("/sdcard").exists()) add("/sdcard")
        add("${vfs.homeDir.absolutePath}:/termux-home")
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

    private suspend fun extractRootfs(tarFile: File, destDir: File) {
        val flag = when {
            tarFile.name.endsWith(".tar.gz")  -> "xzf"
            tarFile.name.endsWith(".tar.xz")  -> "xJf"
            tarFile.name.endsWith(".tar.zst") -> "--zstd -xf"
            else -> "xf"
        }
        val result = executor.execute(
            "${executor.resolveBinary("tar").absolutePath} $flag " +
            "${shellQuote(tarFile.absolutePath)} -C ${shellQuote(destDir.absolutePath)}"
        )
        check(result.isSuccess) { "Extraction failed: ${result.stderr}" }
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
