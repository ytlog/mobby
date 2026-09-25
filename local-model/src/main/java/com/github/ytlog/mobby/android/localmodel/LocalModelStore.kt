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
    val displayName: String = "", val quantization: String = "",
    val source: String = "modelscope",
    val reason: String? = null,
)
@Serializable internal data class InstalledModel(val id: String, val engine: String, val path: String, val size: Long, val sha256: String, val displayName: String = "", val quantization: String = "")
@Serializable internal data class InstallProgress(val id: String, val status: String, val received: Long, val total: Long, val modelId: String = "", val error: String? = null)

internal object MobileGgufPolicy {
    private val quantizations = setOf("q4_k_m", "q5_k_m", "q8_0")
    private val mobileQ4Repos = setOf("ggml-org/Qwen3.5-0.8B-GGUF", "ggml-org/gemma-4-E2B-it-GGUF")

    fun quantization(path: String): String = path.removeSuffix(".gguf").substringAfterLast('-').uppercase()

    fun supports(repo: String, path: String, size: Long): Boolean {
        if (!path.matches(Regex("[A-Za-z0-9._-]+[.]gguf"))) return false
        if (path.startsWith("mmproj-", true) || path.startsWith("mtp-", true)) return false
        if (repo in mobileQ4Repos && quantization(path).equals("Q4_0", true)) {
            return size in 1..3_000_000_000
        }
        return quantization(path).lowercase() in quantizations && size in 1..1_150_000_000
    }
}

internal class LocalModelStore(context: Context) {
    private val root = File(context.filesDir, "local-models").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val installed = File(root, "installed.json")
    val operations = ConcurrentHashMap<String, InstallProgress>()
    private data class Source(val family: String, val name: String)
    private val repos = mapOf(
        "ggml-org/Qwen3.5-0.8B-GGUF" to Source("Qwen", "Qwen 3.5 · 0.8B"),
        "Qwen/Qwen2.5-0.5B-Instruct-GGUF" to Source("Qwen", "Qwen 2.5 · 0.5B Instruct"),
        "Qwen/Qwen3-0.6B-GGUF" to Source("Qwen", "Qwen 3 · 0.6B"),
        "ggml-org/gemma-4-E2B-it-GGUF" to Source("Gemma", "Gemma 4 · E2B Instruct"),
        "ggml-org/gemma-3-270m-it-GGUF" to Source("Gemma", "Gemma 3 · 270M Instruct"),
        "ggml-org/gemma-3-1b-it-GGUF" to Source("Gemma", "Gemma 3 · 1B Instruct"),
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
            if (c.responseCode != 200) error("Model catalog HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private data class Artifact(val path: String, val size: Long, val sha: String, val revision: String)

    private fun artifacts(repo: String, provider: String, revision: String? = null): List<Artifact> = when (provider) {
        "modelscope" -> {
            val suffix = if (revision == null) "?Recursive=true" else "?Revision=$revision&Recursive=true"
            val response = json.parseToJsonElement(readUrl("https://modelscope.cn/api/v1/models/$repo/repo/files$suffix")).jsonObject
            check(response["Success"]?.jsonPrimitive?.content == "true") { "ModelScope catalog unavailable" }
            val files = response["Data"]?.jsonObject?.get("Files") as? JsonArray ?: error("ModelScope files missing")
            files.mapNotNull { item ->
                val obj = item.jsonObject
                val path = obj["Path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val size = obj["Size"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val sha = obj["Sha256"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val rev = obj["Revision"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Artifact(path, size, sha, rev)
            }
        }
        "huggingface", "hf-mirror" -> {
            val base = if (provider == "huggingface") "https://huggingface.co" else "https://hf-mirror.com"
            val rev = revision ?: json.parseToJsonElement(readUrl("$base/api/models/$repo")).jsonObject["sha"]?.jsonPrimitive?.content
                ?: error("Mirror revision missing")
            val tree = json.parseToJsonElement(readUrl("$base/api/models/$repo/tree/$rev?recursive=1")) as JsonArray
            tree.mapNotNull { item ->
                val obj = item.jsonObject
                val path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                val sha = (obj["lfs"] as? JsonObject)?.get("oid")?.jsonPrimitive?.content ?: return@mapNotNull null
                Artifact(path, size, sha, rev)
            }
        }
        else -> error("Unknown model source")
    }

    fun catalog(family: String?, provider: String = "modelscope"): List<Candidate> = repos.flatMap { (repo, source) ->
        if (family != null && !source.family.equals(family, true)) return@flatMap emptyList()
        artifacts(repo, provider).mapNotNull { file ->
            if (!file.sha.matches(Regex("[0-9a-f]{64}")) || !file.revision.matches(Regex("[0-9a-f]{40}")) || !MobileGgufPolicy.supports(repo, file.path, file.size)) return@mapNotNull null
            Candidate(id(source.family, file.path), "llama", source.family, repo, file.revision,
                file.path, file.size, file.sha, true, source.name, MobileGgufPolicy.quantization(file.path), provider)
        }
    }

    /** Resolve again at the pinned revision: the caller cannot inject a download URL or digest. */
    fun resolve(candidate: Candidate): Candidate {
        val source = repos[candidate.repo] ?: error("Unknown model source")
        require(source.family == candidate.family && candidate.engine == "llama" && candidate.installable)
        require(ModelDownloadSource.fromId(candidate.source) != null)
        require(candidate.revision.matches(Regex("[0-9a-f]{40}")))
        require(candidate.id == id(candidate.family, candidate.path) && MobileGgufPolicy.supports(candidate.repo, candidate.path, candidate.size))
        val found = artifacts(candidate.repo, candidate.source, candidate.revision).firstOrNull { it.path == candidate.path }
            ?: error("Artifact disappeared")
        require(found.sha == candidate.sha256 && found.size == candidate.size && found.sha.matches(Regex("[0-9a-f]{64}")))
        return candidate.copy(displayName = source.name, quantization = MobileGgufPolicy.quantization(candidate.path))
    }

    fun install(candidate: Candidate): String {
        val verified = resolve(candidate)
        check(models().none { it.id == verified.id }) { "Model is already installed" }
        check(root.usableSpace > verified.size + 64L * 1024 * 1024) { "Not enough free space" }
        synchronized(activeIds) {
            check(activeIds.add(verified.id)) { "Model download already running" }
        }
        val operation = UUID.randomUUID().toString()
        operations[operation] = InstallProgress(operation, "queued", 0, verified.size, verified.id)
        Thread({ try { download(operation, verified) } finally { synchronized(activeIds) { activeIds.remove(verified.id) } } }, "local-model-download").start()
        return operation
    }

    private fun download(op: String, candidate: Candidate) {
        val target = File(root, "${candidate.id}.gguf")
        val partial = File(root, "${candidate.id}.$op.part")
        try {
            val base = when (candidate.source) {
                "modelscope" -> "https://modelscope.cn/models"
                "huggingface" -> "https://huggingface.co"
                "hf-mirror" -> "https://hf-mirror.com"
                else -> error("Unknown model source")
            }
            downloadFile(URL("$base/${candidate.repo}/resolve/${candidate.revision}/${candidate.path}"), partial, candidate, op)
            check(partial.renameTo(target)) { "Could not publish model" }
            synchronized(this) {
                val updated = models().filterNot { it.id == candidate.id } + InstalledModel(candidate.id, "llama", target.name, candidate.size, candidate.sha256, candidate.displayName, candidate.quantization)
                val next = File(root, "installed.json.tmp")
                next.writeText(json.encodeToString(updated))
                check(next.renameTo(installed)) { "Could not publish metadata" }
            }
            operations[op] = InstallProgress(op, "installed", candidate.size, candidate.size, candidate.id)
        } catch (e: Exception) {
            partial.delete()
            operations[op] = InstallProgress(op, "failed", operations[op]?.received ?: 0, candidate.size, candidate.id, e.message?.take(120) ?: "Download failed")
        }
    }

    private fun downloadFile(url: URL, partial: File, candidate: Candidate, op: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
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
                    output.write(bytes, 0, n); digest.update(bytes, 0, n); received += n
                    operations[op] = InstallProgress(op, "downloading", received, candidate.size, candidate.id)
                    if (received > candidate.size) error("File exceeds expected size")
                }
            } }
        } finally { c.disconnect() }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(received == candidate.size && hash == candidate.sha256) { "Download checksum mismatch" }
    }

    fun file(model: InstalledModel): File = File(root, model.path).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
}
