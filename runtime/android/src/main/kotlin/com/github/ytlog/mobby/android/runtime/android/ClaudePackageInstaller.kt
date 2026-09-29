package com.github.ytlog.mobby.android.runtime.android

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Fetch the official package on the user's device; its program is never distributed in the APK. */
internal class ClaudePackageInstaller(
    private val prefix: File,
    private val fetch: (URL) -> InputStream = ::officialDownload
) {
    suspend fun install() {
        val lock = Json.parseToJsonElement(File(prefix, "share/mobby/agents.lock.json").readText()).jsonObject
        val item = lock.getValue("npm").jsonArray.single { it.jsonObject["name"]?.jsonPrimitive?.content == "@anthropic-ai/claude-code" }.jsonObject
        check(item.getValue("delivery").jsonPrimitive.content == "device-download")
        val integrity = item.getValue("integrity").jsonPrimitive.content
        require(integrity.startsWith("sha512-"))
        val expected = Base64.getDecoder().decode(integrity.removePrefix("sha512-"))
        require(expected.size == 64)
        val url = URL(item.getValue("url").jsonPrimitive.content)
        require(url.protocol == "https" && url.host == "registry.npmjs.org" && url.port == -1 && url.userInfo == null &&
            url.path.startsWith("/@anthropic-ai/claude-code/-/") && url.query == null && url.ref == null)
        val version = item.getValue("version").jsonPrimitive.content
        val target = File(prefix, "lib/node_modules/@anthropic-ai/claude-code")
        val identity = File(target, ".mobby-integrity")
        if (identity.isFile && identity.readText() == integrity && portable.all { File(target, it).isFile }) return
        target.parentFile!!.mkdirs()
        val stage = Files.createTempDirectory(target.parentFile!!.toPath(), ".claude-install-").toFile()
        val archive = File(stage, "download.tgz")
        val unpack = File(stage, "package").apply { mkdir() }
        try {
            val digest = MessageDigest.getInstance("SHA-512")
            fetch(url).use { input -> archive.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= 64L * 1024 * 1024) { "Claude Code download exceeds size limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            check(MessageDigest.isEqual(expected, digest.digest())) { "Claude Code package integrity mismatch" }
            var expanded = 0L
            TarArchiveInputStream(GZIPInputStream(archive.inputStream())).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val parts = entry.name.split('/')
                    check(parts.firstOrNull() == "package" && parts.none { it == ".." || it == "." } && !entry.name.contains('\\')) { "Unsafe Claude Code archive path" }
                    check(!entry.isSymbolicLink && !entry.isLink) { "Claude Code archive links are unsupported" }
                    expanded += entry.size
                    check(entry.size >= 0 && expanded <= 128L * 1024 * 1024) { "Claude Code archive exceeds size limit" }
                    if (entry.isDirectory || parts.getOrNull(1) == "vendor") continue
                    val name = entry.name.removePrefix("package/")
                    check(entry.isFile && name in portable) { "Unexpected Claude Code portable file" }
                    val file = File(unpack, name)
                    check(!file.exists()) { "Duplicate Claude Code archive entry" }
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = tar.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
            check(portable.all { File(unpack, it).isFile }) { "Incomplete Claude Code package" }
            check(Json.parseToJsonElement(File(unpack, "package.json").readText()).jsonObject.getValue("version").jsonPrimitive.content == version)
            File(unpack, ".mobby-integrity").writeText(integrity)
            currentCoroutineContext().ensureActive()
            val previous = File(stage, "previous")
            if (target.exists()) Files.move(target.toPath(), previous.toPath())
            try { Files.move(unpack.toPath(), target.toPath()) }
            catch (error: Exception) {
                if (previous.exists()) Files.move(previous.toPath(), target.toPath())
                throw error
            }
        } finally { stage.deleteRecursively() }
    }

    companion object {
        private val portable = setOf("cli.js", "package.json", "LICENSE.md", "README.md", "sdk-tools.d.ts")
        private fun officialDownload(url: URL): InputStream {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            try {
                check(connection.responseCode == 200) { "Official Claude Code download failed: HTTP ${connection.responseCode}" }
                val input = connection.inputStream
                return object : java.io.FilterInputStream(input) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
    }
}
