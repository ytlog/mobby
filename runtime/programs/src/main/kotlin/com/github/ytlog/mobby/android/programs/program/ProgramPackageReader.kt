package com.github.ytlog.mobby.android.programs.program

import com.github.ytlog.mobby.android.programs.capability.CapabilityKind
import com.github.ytlog.mobby.android.programs.capability.CapabilityRegistry
import com.github.ytlog.mobby.android.programs.capability.EventCapability
import com.github.ytlog.mobby.android.programs.permission.Scope
import kotlinx.serialization.SerializationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

data class PackageProblem(val path: String, val message: String)

class PackageRejected(val problems: List<PackageProblem>) :
    IllegalArgumentException(problems.joinToString("; ") { "${it.path}: ${it.message}" })

/** One validated permission request with its parsed scope. */
data class RequestedPermission(val capability: String, val required: Boolean, val scope: Scope)

/** A validated package read from disk. Nothing has been executed or installed. */
class ProgramPackage internal constructor(
    val manifest: ProgramManifest,
    val manifestJson: String,
    /** Relative paths with `/` separators, sorted. */
    val files: Map<String, File>,
    val permissions: List<RequestedPermission>,
    val checksum: String,
)

data class PackageLimits(val maxFiles: Int = 2_000, val maxFileBytes: Long = 10L * 1024 * 1024, val maxTotalBytes: Long = 50L * 1024 * 1024) {
    companion object { val DEFAULT = PackageLimits() }
}

/**
 * Validates a program directory against the registry: structure, manifest, permissions, config,
 * subscriptions and UI rules. All problems are reported together.
 */
class ProgramPackageReader(private val registry: CapabilityRegistry, private val limits: PackageLimits = PackageLimits.DEFAULT) {
    fun read(directory: File): ProgramPackage {
        val problems = mutableListOf<PackageProblem>()
        fun fail(path: String, message: String) { problems += PackageProblem(path, message) }
        val root = directory.toPath()
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw PackageRejected(listOf(PackageProblem("$", "package must be a directory")))
        val files = collect(root, ::fail)
        if (problems.isNotEmpty()) throw PackageRejected(problems)
        val manifestFile = files["manifest.json"] ?: throw PackageRejected(listOf(PackageProblem("manifest.json", "missing")))
        val manifestJson = manifestFile.readText()
        val manifest = try { ManifestJson.decodeFromString(ProgramManifest.serializer(), manifestJson) }
            catch (error: SerializationException) { throw PackageRejected(listOf(PackageProblem("manifest.json", error.message ?: "invalid JSON"))) }
            catch (error: IllegalArgumentException) { throw PackageRejected(listOf(PackageProblem("manifest.json", error.message ?: "invalid JSON"))) }
        val permissions = checkManifest(manifest, files, ::fail)
        checkUi(manifest, files, ::fail)
        if (problems.isNotEmpty()) throw PackageRejected(problems)
        return ProgramPackage(manifest, manifestJson, files, permissions, digest(files))
    }

    /**
     * Identifies a directory's contents whether or not it is a valid package; for a valid one it equals the
     * package checksum. Links and other non-regular entries count by name only.
     */
    fun fingerprint(directory: File): String {
        val root = directory.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return digest(emptyMap())
        val files = sortedMapOf<String, File?>()
        Files.walk(root).use { stream ->
            stream.filter { it != root && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                files[root.relativize(path).joinToString("/")] = path.toFile().takeIf { Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
            }
        }
        return digest(files)
    }

    /** SHA-256 over sorted paths and contents. */
    private fun digest(files: Map<String, File?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, file) ->
            digest.update(path.toByteArray()); digest.update(0)
            file?.inputStream()?.use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun collect(root: Path, fail: (String, String) -> Unit): Map<String, File> {
        val result = sortedMapOf<String, File>()
        var total = 0L
        Files.walk(root).use { stream ->
            stream.filter { it != root }.forEach { path ->
                val relative = root.relativize(path).joinToString("/")
                when {
                    Files.isSymbolicLink(path) -> fail(relative, "symbolic links are not allowed")
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> fail(relative, "only regular files are allowed")
                    relative.any { it.isISOControl() } || relative.split('/').any { it.isEmpty() || it == "." || it == ".." } -> fail(relative, "invalid path")
                    else -> {
                        val size = Files.size(path)
                        if (size > limits.maxFileBytes) fail(relative, "file exceeds ${limits.maxFileBytes} bytes")
                        total += size
                        result[relative] = path.toFile()
                    }
                }
            }
        }
        if (result.size > limits.maxFiles) fail("$", "package has more than ${limits.maxFiles} files")
        if (total > limits.maxTotalBytes) fail("$", "package exceeds ${limits.maxTotalBytes} bytes")
        return result
    }

    private fun checkManifest(manifest: ProgramManifest, files: Map<String, File>, fail: (String, String) -> Unit): List<RequestedPermission> {
        if (manifest.schema != PROGRAM_SCHEMA) fail("schema", "must be $PROGRAM_SCHEMA")
        if (!manifest.id.matches(PROGRAM_ID)) fail("id", "must match ${PROGRAM_ID.pattern}")
        if (!manifest.version.matches(VERSION)) fail("version", "must match ${VERSION.pattern}")
        if (manifest.name.isBlank() || manifest.name.length > 60) fail("name", "must be 1-60 characters")
        if (manifest.background == null && manifest.ui == null) fail("$", "background or ui is required")
        manifest.background?.let { if (!it.endsWith(".js") && !it.endsWith(".mjs") && !it.endsWith(".cjs") || it !in files) fail("background", "must name a JavaScript file in the package") }
        manifest.ui?.entry?.let { if (!it.endsWith(".html") || it !in files) fail("ui.entry", "must name an HTML file in the package") }
        val permissions = manifest.permissions.mapIndexedNotNull { index, request ->
            val path = "permissions[$index]"
            val handler = registry[request.capability]
            if (handler == null) { fail("$path.capability", "unknown capability ${request.capability}"); return@mapIndexedNotNull null }
            val (scope, problems) = Scope.parse(handler.descriptor.scope, request.scope)
            problems.forEach { fail("$path.scope", it) }
            scope?.let { RequestedPermission(request.capability, request.required, it) }
        }
        manifest.permissions.groupBy { it.capability }.filterValues { it.size > 1 }.keys.forEach { fail("permissions", "duplicate capability $it") }
        val byCapability = permissions.associateBy { it.capability }
        manifest.config.forEachIndexed { index, field -> checkConfig(field, "config[$index]", byCapability, fail) }
        manifest.config.groupBy { it.key }.filterValues { it.size > 1 }.keys.forEach { fail("config", "duplicate key $it") }
        manifest.subscriptions.forEachIndexed { index, subscription ->
            val path = "subscriptions[$index]"
            if (!subscription.id.matches(LOCAL_ID)) fail("$path.id", "must match ${LOCAL_ID.pattern}")
            if (!subscription.handler.matches(LOCAL_ID)) fail("$path.handler", "must match ${LOCAL_ID.pattern}")
            if (manifest.background == null) fail(path, "subscriptions need background logic")
            val handler = registry[subscription.event]
            if (handler == null || handler.descriptor.kind != CapabilityKind.EVENT || handler !is EventCapability) {
                fail("$path.event", "${subscription.event} is not an event capability"); return@forEachIndexed
            }
            val requested = byCapability[subscription.event]
            if (requested == null) { fail("$path.event", "${subscription.event} must also be requested in permissions"); return@forEachIndexed }
            handler.validateFilter(subscription.filter, requested.scope).forEach { fail("$path.filter", it) }
        }
        manifest.subscriptions.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { fail("subscriptions", "duplicate id $it") }
        return permissions
    }

    private fun checkConfig(field: ConfigField, path: String, permissions: Map<String, RequestedPermission>, fail: (String, String) -> Unit) {
        if (!field.key.matches(LOCAL_ID)) fail("$path.key", "must match ${LOCAL_ID.pattern}")
        if (field.label.isBlank() || field.label.length > 60) fail("$path.label", "must be 1-60 characters")
        val type = ConfigType.of(field.type) ?: run { fail("$path.type", "unknown type ${field.type}"); return }
        if ((type == ConfigType.ENUM) != (field.options != null)) fail("$path.options", "options are required for enum and only for enum")
        field.options?.let { if (it.isEmpty() || it.size > 32 || it.toSet().size != it.size || it.any { o -> o.isBlank() || o.length > 60 }) fail("$path.options", "1-32 distinct options of 1-60 characters") }
        if ((type == ConfigType.SECRET) != (field.domains != null)) fail("$path.domains", "domains are required for secret and only for secret")
        if (type == ConfigType.SECRET && field.default != null) fail("$path.default", "secrets have no default")
        field.domains?.let { domains ->
            val net = permissions["net.http"]
            if (net == null) fail("$path.domains", "secret fields need a net.http permission")
            else domains.filterNot { net.scope.allowsHost(it.removePrefix("*.")) || it in net.scope.domains.orEmpty() }
                .forEach { fail("$path.domains", "$it is outside the requested net.http domains") }
        }
    }

    private fun checkUi(manifest: ProgramManifest, files: Map<String, File>, fail: (String, String) -> Unit) {
        if (manifest.ui == null) return
        files.filterKeys { it.endsWith(".html") || it.endsWith(".htm") }.forEach { (path, file) ->
            val html = file.readText()
            INLINE_SCRIPT.findAll(html).forEach { _ -> fail(path, "inline scripts are not allowed; load scripts from files") }
            EVENT_ATTRIBUTE.find(html)?.let { fail(path, "inline event handler ${it.groupValues[1]} is not allowed") }
            TAG.findAll(html).forEach { tag ->
                val element = tag.groupValues[1].lowercase()
                EXTERNAL_REFERENCE.findAll(tag.value).forEach { attribute ->
                    // Links leave the program only after the user confirms opening a browser.
                    if (!(element == "a" && attribute.groupValues[1].lowercase() == "href"))
                        fail(path, "external reference ${attribute.groupValues[2]} is not allowed")
                }
            }
            if (JAVASCRIPT_URL.containsMatchIn(html)) fail(path, "javascript: URLs are not allowed")
        }
        files.filterKeys { it.endsWith(".css") }.forEach { (path, file) ->
            CSS_EXTERNAL.findAll(file.readText()).forEach { fail(path, "external reference ${it.groupValues[1]} is not allowed") }
        }
    }

    companion object {
        val PROGRAM_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
        val VERSION = Regex("[0-9A-Za-z.+-]{1,32}")
        val LOCAL_ID = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
        private val INLINE_SCRIPT = Regex("<script(?![^>]*\\bsrc\\s*=)[^>]*>\\s*\\S", RegexOption.IGNORE_CASE)
        private val EVENT_ATTRIBUTE = Regex("<[^>]*\\s(on[a-z]+)\\s*=", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<([A-Za-z][A-Za-z0-9-]*)\\b[^>]*>")
        private val EXTERNAL_REFERENCE = Regex("\\b(src|href|action|srcset|poster|data)\\s*=\\s*[\"']?\\s*((?:https?:)?//[^\"'\\s>]+)", RegexOption.IGNORE_CASE)
        private val JAVASCRIPT_URL = Regex("[\"'\\s=]javascript:", RegexOption.IGNORE_CASE)
        private val CSS_EXTERNAL = Regex("(?:url\\(|@import)\\s*[\"']?\\s*((?:https?:)?//[^\"')\\s]+)", RegexOption.IGNORE_CASE)
    }
}
