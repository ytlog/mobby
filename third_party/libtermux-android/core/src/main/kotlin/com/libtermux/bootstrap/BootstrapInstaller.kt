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
package com.libtermux.bootstrap

import android.content.Context
import com.libtermux.TermuxConfig
import com.libtermux.fs.VirtualFileSystem
import com.libtermux.utils.ArchUtils.resolved
import com.libtermux.utils.FileUtils.deleteRecursivelyQuiet
import com.libtermux.utils.FileUtils.extractZip
import com.libtermux.utils.FileUtils.makeExecutable
import com.libtermux.utils.FileUtils.processSymlinks
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class InstallState {
    object Checking       : InstallState()
    object AlreadyInstalled : InstallState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : InstallState()
    data class Extracting(val progress: Float) : InstallState()
    object SettingPermissions : InstallState()
    object ProcessingSymlinks : InstallState()
    object Verifying      : InstallState()
    object Completed      : InstallState()
    data class Failed(val error: String, val cause: Throwable? = null) : InstallState()
}

@Serializable
internal data class GithubRelease(val tag_name: String, val published_at: String? = null)

/**
 * Auto-resolves the latest Termux bootstrap release tag from GitHub API.
 * Falls back to a hardcoded list of known recent tags if the API is unreachable.
 */
internal object BootstrapReleaseResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val API_URL = "https://api.github.com/repos/termux/termux-packages/releases?per_page=50"

    // Fallback tags (newest first)
    private val FALLBACK_TAGS = listOf(
        "bootstrap-2026.05.24-r1+apt.android-7",
        "bootstrap-2026.05.17-r1+apt.android-7",
        "bootstrap-2026.05.10-r1+apt.android-7",
        "bootstrap-2026.05.03-r1+apt.android-7"
    )

    fun resolveLatestTag(): String {
        val apiTag = fetchLatestTagFromApi()
        if (apiTag != null) {
            TermuxLogger.i("Resolved latest bootstrap tag from GitHub API: $apiTag")
            return apiTag
        }
        TermuxLogger.w("GitHub API unavailable, using fallback bootstrap tag")
        return FALLBACK_TAGS.first()
    }

    private fun fetchLatestTagFromApi(): String? {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "LibTermux-Android/1.0.0")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TermuxLogger.w("GitHub API returned ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = Json { ignoreUnknownKeys = true }
                val releases = json.decodeFromString<List<GithubRelease>>(body)

                releases.asSequence()
                    .map { it.tag_name }
                    .filter {
                        it.startsWith("bootstrap-") &&
                        it.contains("apt.android-7") &&
                        !it.contains("glibc")
                    }
                    .maxWithOrNull(compareBy { parseVersionScore(it) })
            }
        } catch (e: Exception) {
            TermuxLogger.e("Failed to fetch bootstrap releases from GitHub API", e)
            null
        }
    }

    private fun parseVersionScore(tag: String): Long {
        val regex = Regex("""bootstrap-(\d{4})\.(\d{2})\.(\d{2})-r(\d+)""")
        val match = regex.find(tag) ?: return 0
        val (year, month, day, rev) = match.destructured
        return year.toLong() * 100000000L +
               month.toLong() * 1000000L +
               day.toLong() * 10000L +
               rev.toLong()
    }
}

class BootstrapInstaller(
    private val context: Context,
    private val config: TermuxConfig,
    private val vfs: VirtualFileSystem,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "LibTermux-Android/1.0.0")
                .build()
            chain.proceed(request)
        }
        .build()

    fun install(forceReinstall: Boolean = false): Flow<InstallState> = flow {
        emit(InstallState.Checking)

        if (!forceReinstall && vfs.isBootstrapInstalled) {
            TermuxLogger.i("Bootstrap already installed, skipping.")
            emit(InstallState.AlreadyInstalled)
            return@flow
        }

        try {
            val arch = config.architecture.resolved()
            val url = config.customBootstrapUrl ?: run {
                val tag = if (config.bootstrapVersion == TermuxConfig.LATEST_BOOTSTRAP) {
                    BootstrapReleaseResolver.resolveLatestTag()
                } else {
                    val version = config.bootstrapVersion
                    when {
                        version.contains("apt") -> "bootstrap-$version"
                        else -> "bootstrap-$version+apt.android-7"
                    }
                }
                val encodedTag = tag.replace("+", "%2B")
                val baseUrl = "https://github.com/termux/termux-packages/releases/download"
                "$baseUrl/$encodedTag/bootstrap-${arch.termuxName}.zip"
            }

            TermuxLogger.i("Downloading bootstrap for ${arch.termuxName}: $url")

            val zipFile = File(context.cacheDir, "bootstrap-${arch.termuxName}.zip")
            var totalBytes = -1L

            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorMsg = when (response.code) {
                        404 -> "HTTP 404: Bootstrap not found. Architecture '${arch.termuxName}' may not be available. Try setting a customBootstrapUrl."
                        403 -> "HTTP 403: Rate limited or forbidden by GitHub. Try again later or set a customBootstrapUrl."
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    emit(InstallState.Failed(errorMsg))
                    return@flow
                }
                totalBytes = response.body?.contentLength() ?: -1L

                zipFile.outputStream().use { out ->
                    val source = response.body?.byteStream() ?: run {
                        emit(InstallState.Failed("Empty response body from server"))
                        return@flow
                    }

                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var bytesCopied = 0L
                    var lastProgress = 0f

                    while (source.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        if (totalBytes > 0) {
                            val progress = bytesCopied.toFloat() / totalBytes
                            if (progress - lastProgress >= 0.01f || progress >= 1f) {
                                lastProgress = progress
                                emit(InstallState.Downloading(progress, bytesCopied, totalBytes))
                            }
                        }
                    }
                }
            }

            if (totalBytes <= 0) {
                emit(InstallState.Downloading(1f, zipFile.length(), zipFile.length()))
            }

            TermuxLogger.i("Extracting bootstrap to ${vfs.prefixDir}")
            if (forceReinstall) {
                vfs.prefixDir.deleteRecursivelyQuiet()
                vfs.clearBootstrapMarker()
            }
            vfs.prefixDir.mkdirs()

            var lastExtractProgress = 0f
            zipFile.inputStream().use { stream ->
                extractZip(
                    zipStream   = stream,
                    destDir     = vfs.prefixDir,
                    onProgress  = { p ->
                        if (p - lastExtractProgress >= 0.05f || p >= 1f) {
                            lastExtractProgress = p
                            emit(InstallState.Extracting(p))
                        }
                    },
                    totalBytes  = zipFile.length(),
                )
            }
            emit(InstallState.Extracting(1f))

            // ===== SYMLINKS FIX: emit state first, then call regular function =====
            emit(InstallState.ProcessingSymlinks)
            val symlinksFile = File(vfs.prefixDir, "SYMLINKS.txt")
            if (symlinksFile.exists()) {
                processSymlinks(symlinksFile, vfs.prefixDir)
                symlinksFile.delete()
            } else {
                TermuxLogger.w("No SYMLINKS.txt found in bootstrap. Symlinks may be broken.")
            }

            emit(InstallState.SettingPermissions)
            makeExecutable(vfs.prefixDir)

            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "1777", vfs.tmpDir.absolutePath)).waitFor()
            }

            emit(InstallState.Verifying)
            val shell = File(vfs.binDir, "bash")
            val sh    = File(vfs.binDir, "sh")
            if (!shell.exists() && !sh.exists()) {
                emit(InstallState.Failed("Bootstrap verification failed: no shell found in ${vfs.binDir}"))
                return@flow
            }

            val coreBins = listOf("busybox", "dpkg", "pkg", "apt")
            val missing = coreBins.filter { !File(vfs.binDir, it).exists() }
            if (missing.isNotEmpty()) {
                TermuxLogger.w("Missing core binaries: $missing")
            }

            vfs.markBootstrapInstalled()
            if (zipFile.exists()) zipFile.delete()

            TermuxLogger.i("Bootstrap installation complete! Environment ready at ${vfs.prefixDir}")
            emit(InstallState.Completed)

        } catch (e: Exception) {
            TermuxLogger.e("Bootstrap installation failed", e)
            emit(InstallState.Failed(e.message ?: "Unknown error", e))
        }
    }.flowOn(Dispatchers.IO)

    fun uninstall() {
        vfs.resetAll()
        TermuxLogger.i("Bootstrap uninstalled.")
    }
}
