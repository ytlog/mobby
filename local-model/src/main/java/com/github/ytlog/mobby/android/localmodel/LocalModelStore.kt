package com.github.ytlog.mobby.android.localmodel

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable internal data class Candidate(
    val id: String, val engine: String, val family: String, val repo: String, val revision: String,
    val path: String, val size: Long, val sha256: String, val installable: Boolean,
    val reason: String? = null,
)
@Serializable internal data class InstalledModel(val id: String, val engine: String, val path: String, val size: Long, val sha256: String)
@Serializable internal data class InstallProgress(val id: String, val status: String, val received: Long, val total: Long, val error: String? = null)

internal class LocalModelStore(context: Context) {
    private val root = File(context.filesDir, "local-models").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val installed = File(root, "installed.json")
    val operations = ConcurrentHashMap<String, InstallProgress>()
    private val repos = mapOf(
        "Qwen/Qwen3-0.6B-GGUF" to "Qwen",
        "ggml-org/gemma-3-270m-it-GGUF" to "Gemma",
    )
    private val activeIds = mutableSetOf<String>()

    private fun id(family: String, path: String): String =
        "${family.lowercase()}-${path.removeSuffix(".gguf").lowercase().replace(Regex("[^a-z0-9-]"), "-")}"

    @Synchronized fun models(): List<InstalledModel> = if (installed.exists())
        runCatching { json.decodeFromString<List<InstalledModel>>(installed.readText()) }.getOrDefault(emptyList())
    else emptyList()

    private fun readUrl(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 15_000; setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) error("Hugging Face HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun catalog(family: String?): List<Candidate> = repos.flatMap { (repo, name) ->
        if (family != null && !name.equals(family, true)) return@flatMap emptyList()
        val info = json.parseToJsonElement(readUrl("https://huggingface.co/api/models/$repo")).jsonObject
        val revision = info["sha"]?.jsonPrimitive?.content ?: error("Missing repository revision")
        val tree = json.parseToJsonElement(readUrl("https://huggingface.co/api/models/$repo/tree/$revision?recursive=1")) as JsonArray
        tree.mapNotNull { item ->
            val obj = item.jsonObject
            val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!path.lowercase().endsWith(".gguf") || path.contains('/')) return@mapNotNull null
            val lfs = obj["lfs"] as? JsonObject ?: return@mapNotNull null
            val sha = lfs["oid"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            if (!sha.matches(Regex("[0-9a-f]{64}")) || size <= 0) return@mapNotNull null
            Candidate(id(name, path),
                "llama", name, repo, revision, path, size, sha, true)
        }
    }

    /** Resolve again at the pinned revision: the caller cannot inject a download URL or digest. */
    fun resolve(candidate: Candidate): Candidate {
        require(repos[candidate.repo] == candidate.family && candidate.engine == "llama")
        require(candidate.revision.matches(Regex("[0-9a-f]{40}")))
        require(candidate.id == id(candidate.family, candidate.path) && candidate.path.matches(Regex("[A-Za-z0-9._-]+[.]gguf")))
        val tree = json.parseToJsonElement(readUrl("https://huggingface.co/api/models/${candidate.repo}/tree/${candidate.revision}?recursive=1")) as JsonArray
        val found = tree.map { it.jsonObject }.firstOrNull { it["path"]?.jsonPrimitive?.content == candidate.path }
            ?: error("Artifact disappeared")
        val lfs = found["lfs"] as? JsonObject ?: error("No verified SHA-256")
        val sha = lfs["oid"]?.jsonPrimitive?.content ?: error("No verified SHA-256")
        val size = found["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: error("No size")
        require(sha == candidate.sha256 && size == candidate.size && sha.matches(Regex("[0-9a-f]{64}")) && size > 0)
        return candidate
    }

    fun install(candidate: Candidate): String {
        val verified = resolve(candidate)
        check(models().none { it.id == verified.id }) { "Model is already installed" }
        check(root.usableSpace > verified.size + 64L * 1024 * 1024) { "Not enough free space" }
        synchronized(activeIds) {
            check(activeIds.add(verified.id)) { "Model download already running" }
        }
        val operation = UUID.randomUUID().toString()
        operations[operation] = InstallProgress(operation, "queued", 0, verified.size)
        Thread({ try { download(operation, verified) } finally { synchronized(activeIds) { activeIds.remove(verified.id) } } }, "local-model-download").start()
        return operation
    }

    private fun download(op: String, candidate: Candidate) {
        val target = File(root, "${candidate.id}.gguf")
        val partial = File(root, "${candidate.id}.$op.part")
        try {
            val url = URL("https://huggingface.co/${candidate.repo}/resolve/${candidate.revision}/${candidate.path}")
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                check(c.responseCode == 200) { "Download HTTP ${c.responseCode}" }
                check(c.url.protocol == "https") { "Insecure download redirect" }
                c.inputStream.use { input -> partial.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        output.write(bytes, 0, n); digest.update(bytes, 0, n); read += n
                        operations[op] = InstallProgress(op, "downloading", read, candidate.size)
                        if (read > candidate.size) error("File exceeds expected size")
                    }
                } }
            } finally { c.disconnect() }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(read == candidate.size && hash == candidate.sha256) { "Download checksum mismatch" }
            check(partial.renameTo(target)) { "Could not publish model" }
            synchronized(this) {
                val updated = models().filterNot { it.id == candidate.id } + InstalledModel(candidate.id, "llama", target.name, read, hash)
                val next = File(root, "installed.json.tmp")
                next.writeText(json.encodeToString(updated))
                check(next.renameTo(installed)) { "Could not publish metadata" }
            }
            operations[op] = InstallProgress(op, "installed", read, read)
        } catch (e: Exception) {
            partial.delete()
            operations[op] = InstallProgress(op, "failed", operations[op]?.received ?: 0, candidate.size, e.message?.take(120) ?: "Download failed")
        }
    }

    fun file(model: InstalledModel): File = File(root, model.path).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
}
