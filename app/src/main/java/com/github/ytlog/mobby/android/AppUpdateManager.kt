package com.github.ytlog.mobby.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Owns the entire GitHub Release update path. The UI only observes state and invokes actions. */
class AppUpdateManager(private val context: Context) {
    enum class Phase { IDLE, CHECKING, CURRENT, DOWNLOADING, READY, INSTALLING, NEEDS_PERMISSION, ERROR }
    data class State(val phase: Phase = Phase.IDLE, val version: String? = null, val bytes: Long = 0,
        val total: Long = 0, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("mobby.updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "updates")
    private var release: Release? = null
    private var busy = false
    var state by mutableStateOf(restoredState())
        private set
    var autoCheck by mutableStateOf(prefs.getBoolean("auto_check", true))
        private set
    var dismissedVersion by mutableStateOf<String?>(null)
        private set

    fun setAutoCheck(enabled: Boolean): Boolean {
        if (!prefs.edit().putBoolean("auto_check", enabled).commit()) return false
        autoCheck = enabled
        if (enabled) checkIfDue()
        return true
    }

    fun dismissNotice() { dismissedVersion = state.version }

    fun checkIfDue() {
        if (!autoCheck || busy || state.phase == Phase.READY || state.phase == Phase.INSTALLING) return
        val last = prefs.getLong("last_check", 0)
        val now = System.currentTimeMillis()
        if (state.phase == Phase.CURRENT && last > 0 && now >= last && now - last < 24L * 60 * 60 * 1000) return
        check()
    }

    fun check() {
        if (busy) return
        scope.launch {
            busy = true
            state = State(Phase.CHECKING)
            try {
                val found = withContext(Dispatchers.IO) { fetchRelease() }
                if (found == null || found.versionCode <= installedVersionCode()) {
                    release = null
                    state = State(Phase.CURRENT)
                } else {
                    release = found
                    val apk = File(directory, found.apk)
                    if (withContext(Dispatchers.IO) { apk.isFile && validApk(apk, found) }) {
                        state = State(Phase.READY, found.version)
                    } else {
                        withContext(Dispatchers.IO) { download(found) }
                        state = State(Phase.READY, found.version)
                    }
                }
                prefs.edit().putLong("last_check", System.currentTimeMillis())
                    .putString("last_outcome", if (state.phase == Phase.CURRENT) "current" else "ready")
                    .putLong("checked_app_code", installedVersionCode()).apply()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = State(Phase.ERROR, message = e.message ?: "Update check failed") }
            finally { busy = false }
        }
    }

    fun install() {
        val candidate = release ?: return
        if (busy || state.phase !in setOf(Phase.READY, Phase.NEEDS_PERMISSION)) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = State(Phase.NEEDS_PERMISSION, candidate.version)
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                state = State(Phase.ERROR, candidate.version, message = e.message ?: "Install permission settings unavailable")
            }
            return
        }
        scope.launch {
            busy = true
            try {
                val apk = File(directory, candidate.apk)
                require(withContext(Dispatchers.IO) { validApk(apk, candidate) }) { "Downloaded APK failed verification" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                state = State(Phase.INSTALLING, candidate.version)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = State(Phase.ERROR, candidate.version, message = e.message ?: "Installer unavailable") }
            finally { busy = false }
        }
    }

    fun onResume() {
        val candidate = release ?: return
        if (state.phase == Phase.INSTALLING || state.phase == Phase.NEEDS_PERMISSION) {
            state = if (installedVersionCode() >= candidate.versionCode) State(Phase.CURRENT)
                else State(Phase.READY, candidate.version)
        }
    }

    private fun installedVersionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun restoredState(): State {
        val last = prefs.getLong("last_check", 0)
        val now = System.currentTimeMillis()
        return if (prefs.getString("last_outcome", null) == "current" &&
            prefs.getLong("checked_app_code", -1) == installedVersionCode() &&
            last > 0 && now >= last && now - last < 24L * 60 * 60 * 1000) State(Phase.CURRENT)
        else State()
    }

    private fun fetchRelease(): Release? {
        val response = readText("https://api.github.com/repos/ytlog/mobby/releases/latest", 256 * 1024, allowNotFound = true)
            ?: return null
        val json = JSONObject(response)
        require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "No stable release" }
        val tag = json.getString("tag_name")
        require(tag.matches(Regex("v[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "Invalid release tag" }
        val assets = json.getJSONArray("assets")
        val metadataName = "update.json"
        if ((0 until assets.length()).none { assets.getJSONObject(it).optString("name") == metadataName }) {
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            if (tag == "v$installed") return null // Releases predating update.json cannot describe an upgrade.
            error("Release has no update metadata")
        }
        val base = "https://github.com/ytlog/mobby/releases/download/$tag/"
        val manifest = Release.parse(readText(base + metadataName, 16 * 1024)!!, tag)
        require((0 until assets.length()).any { assets.getJSONObject(it).optString("name") == manifest.apk }) { "Release has no APK" }
        return manifest.copy(url = base + manifest.apk)
    }

    private fun download(candidate: Release) {
        directory.mkdirs()
        val part = File(directory, "download.part")
        val target = File(directory, candidate.apk)
        part.delete()
        try {
            val connection = open(candidate.url)
            try {
                require(connection.responseCode == 200) { "APK download failed: HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong
                require(total in 1..MAX_APK_BYTES) { "APK size unavailable or too large" }
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                connection.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= total) { "APK length mismatch" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (bytes % (1024 * 1024) < buffer.size) state = State(Phase.DOWNLOADING, candidate.version, bytes, total)
                    }
                    output.fd.sync()
                } }
                require(bytes == total) { "APK length mismatch" }
                require(digest.digest().hex() == candidate.sha256) { "APK checksum mismatch" }
                require(validArchive(part, candidate)) { "APK identity or signature mismatch" }
                target.delete()
                require(part.renameTo(target)) { "Could not save verified APK" }
            } finally { connection.disconnect() }
        } finally { part.delete() }
    }

    private fun validApk(file: File, candidate: Release): Boolean = file.isFile && file.length() in 1..MAX_APK_BYTES &&
        sha256(file) == candidate.sha256 && validArchive(file, candidate)

    @Suppress("DEPRECATION")
    private fun validArchive(file: File, candidate: Release): Boolean {
        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flag) ?: return false
        val installed = pm.getPackageInfo(context.packageName, flag)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners?.toList().orEmpty() else installed.signatures?.toList().orEmpty()
        val archiveSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners?.toList().orEmpty() else archive.signatures?.toList().orEmpty()
        return archive.packageName == context.packageName && archive.versionName == candidate.version && code == candidate.versionCode &&
            code > installedVersionCode() && installedSigners.isNotEmpty() && installedSigners.toSet() == archiveSigners.toSet()
    }

    private fun readText(url: String, limit: Int, allowNotFound: Boolean = false): String? {
        val connection = open(url)
        try {
            if (allowNotFound && connection.responseCode == 404) return null
            require(connection.responseCode == 200) { "Update server returned HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= limit) { "Update metadata too large" }
                    output.write(buffer, 0, n)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "mobby-android-updater")
        return connection
    }

    internal data class Release(val version: String, val versionCode: Long, val apk: String, val sha256: String,
        val url: String = "") {
        companion object {
            fun parse(raw: String, tag: String): Release {
                val json = JSONObject(raw)
                require(json.getInt("schemaVersion") == 1) { "Unsupported update metadata" }
                val version = json.getString("version")
                val apk = json.getString("apk")
                val sha = json.getString("sha256")
                val code = json.getLong("versionCode")
                require(tag == "v$version" && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "Release version mismatch" }
                require(code > 0) { "Invalid version code" }
                require(apk == "mobby-v$version-arm64-v8a.apk") { "Unexpected APK name" }
                require(sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK checksum" }
                return Release(version, code, apk, sha)
            }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").also { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
    }.digest().hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object { const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024 }
}

class UpdateFileProvider : FileProvider()
