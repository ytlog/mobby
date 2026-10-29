package com.github.ytlog.mobby.android.programs.dev

import com.github.ytlog.mobby.android.device.ProgramDevelopmentPort
import com.github.ytlog.mobby.android.programs.ProgramPlatform
import com.github.ytlog.mobby.android.programs.call.DryRunScope
import com.github.ytlog.mobby.android.programs.capability.PHASE_ONE_DEVICE_CAPABILITIES
import com.github.ytlog.mobby.android.programs.event.ProgramEvent
import com.github.ytlog.mobby.android.programs.program.PackageRejected
import com.github.ytlog.mobby.android.programs.program.ProgramPackageReader
import com.github.ytlog.mobby.android.programs.run.LaunchUnavailable
import com.github.ytlog.mobby.android.programs.run.RunTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/**
 * Development commands for chat runs. Nothing here installs, enables or grants: validate reads,
 * dry-run executes a throwaway copy with simulated effects, records reads attached programs only.
 */
class DevelopmentService(private val platform: ProgramPlatform) : ProgramDevelopmentPort {
    private val reader get() = ProgramPackageReader(platform.registry)

    override fun catalog(): String = DevelopmentGuide.render(platform.registry.descriptors.map { it to platform.registry[it.id]!!.availability() })

    override fun validate(path: String): JsonObject {
        val dir = resolve(path)
        return try {
            val pkg = reader.read(dir)
            val scripts = pkg.files.keys.filter { it.substringAfterLast('.') in SCRIPTS }
            // A page that does not parse never runs and shows nothing, and dry runs do not load pages, so parse every script here.
            val syntax = try { Result.success(platform.checkSyntax(dir, scripts)) } catch (error: LaunchUnavailable) { Result.failure(error) }
            val unparsed = syntax.getOrNull().orEmpty()
            buildJsonObject {
                put("valid", unparsed.isEmpty())
                put("id", pkg.manifest.id); put("version", pkg.manifest.version)
                put("files", pkg.files.size)
                if (unparsed.isNotEmpty()) put("problems", JsonArray(unparsed.map { JsonPrimitive("${it.path}:${it.line?.let { line -> "$line:" } ?: ""} ${it.message}") }))
                val notChecked = syntax.exceptionOrNull()?.let { "scripts were not syntax-checked: ${it.message}" }
                put("warnings", JsonArray((warnings(dir, pkg.manifest.background) + listOfNotNull(notChecked)).map(::JsonPrimitive)))
            }
        } catch (error: PackageRejected) {
            buildJsonObject { put("valid", false); put("problems", JsonArray(error.problems.map { JsonPrimitive("${it.path}: ${it.message}") })) }
        }
    }

    override fun dryRun(path: String, events: String, timeoutMillis: Long, allowedDeviceActions: Map<String, Set<String>>): JsonObject = runBlocking {
        val dir = resolve(path)
        val pkg = try { reader.read(dir) } catch (error: PackageRejected) {
            return@runBlocking buildJsonObject { put("ran", false); put("problems", JsonArray(error.problems.map { JsonPrimitive("${it.path}: ${it.message}") })) }
        }
        val entry = pkg.manifest.background ?: return@runBlocking buildJsonObject { put("ran", false); put("problems", JsonArray(listOf(JsonPrimitive("no background logic to run")))) }
        val parsed = runCatching { Json.parseToJsonElement(events).jsonArray }.getOrNull()
            ?: return@runBlocking buildJsonObject { put("ran", false); put("problems", JsonArray(listOf(JsonPrimitive("events must be a JSON array")))) }
        val token = UUID.randomUUID().toString()
        val workspace = File(platform.layout.data(pkg.manifest.id), "dry-run/$token").apply { mkdirs() }
        val copy = File(workspace, "package")
        pkg.files.forEach { (relative, source) -> source.copyTo(File(copy, relative).apply { parentFile!!.mkdirs() }) }
        val allowed = PHASE_ONE_DEVICE_CAPABILITIES.filter { spec -> spec.action in allowedDeviceActions[spec.plugin].orEmpty() }.map { it.id }.toSet()
        val scope = DryRunScope(allowed, pkg.permissions.associate { it.capability to it.scope })
        val simulated = parsed.mapIndexed { index, value ->
            val event = value.jsonObject
            ProgramEvent("dry-$index", (event["type"] as? JsonPrimitive)?.contentOrNull ?: "dry.run",
                (event["handler"] as? JsonPrimitive)?.contentOrNull ?: "main", null, platform.now(), event["payload"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        val runId = platform.runs.trigger(RunTarget(pkg.manifest.id, "dry-run", copy, entry, File(workspace, "files")), simulated, "dry_run", scope)
        val finished = withTimeoutOrNull(timeoutMillis) {
            while (platform.dao.run(runId)?.endedAt == null) delay(100)
            true
        } ?: false
        if (!finished) {
            platform.runs.stopRun(runId, "dry_run_timeout")
            withTimeoutOrNull(5_000) { while (platform.dao.run(runId)?.endedAt == null) delay(100) }
        }
        val run = platform.dao.run(runId)
        val operations = platform.dao.operations(pkg.manifest.id, 500).filter { it.caller == "run:$runId" }.sortedBy { it.acceptedAt }
        val (uiPosts, logs) = platform.dao.logs(pkg.manifest.id, 200).filter { it.caller == "run:$runId" }.sortedBy { it.at }
            .partition { it.message == ProgramPlatform.SIMULATED_UI_POST }
        val output = platform.runLog(runId)
        val handlers = output?.let(::registeredHandlers)
        // Throwaway storage lives under the run's own dry-run directory; both it and the package copy go.
        val storage = File(platform.layout.data(pkg.manifest.id), "dry-run/$runId")
        platform.releaseStorage(storage)
        platform.dao.deleteKvs("${pkg.manifest.id}#dry:$runId")
        storage.deleteRecursively()
        workspace.deleteRecursively()
        buildJsonObject {
            put("ran", true)
            put("note", "Simulated: commands and network requests were not sent; storage used throwaway data that is now deleted.")
            put("outcome", run?.outcome); put("reason", run?.reason); put("timedOut", !finished)
            put("calls", JsonArray(operations.map { op ->
                buildJsonObject {
                    put("capability", op.capability); put("status", op.status); put("effect", op.effect)
                    op.errorCode?.let { put("error", it) }; op.errorReason?.let { put("reason", it) }
                }
            }))
            put("logs", JsonArray(logs.map { buildJsonObject { put("message", it.message); it.dataJson?.let { d -> put("data", d) } } }))
            put("uiMessages", JsonArray(uiPosts.mapNotNull { row -> row.dataJson?.let { Json.parseToJsonElement(it).jsonObject["message"] } }))
            handlers?.let { registered ->
                put("handlers", JsonArray(registered.map(::JsonPrimitive)))
                val exercised = simulated.map { it.handler }.toSet()
                put("notExercised", JsonArray(registered.filter { it !in exercised }.map(::JsonPrimitive)))
            }
            output?.let { put("output", it.takeLast(16_000)) }
        }
    }

    /** The runner prints the handlers a program registered once its entry has loaded; the run log marks it as `[out]`. */
    private fun registeredHandlers(output: String): List<String>? = output.lineSequence().map { it.removePrefix("[out] ") }
        .filter { it.startsWith(HANDLERS) }.lastOrNull()
        ?.let { line -> runCatching { Json.parseToJsonElement(line.removePrefix(HANDLERS)).jsonArray.map { it.jsonPrimitive.content } }.getOrNull() }

    override fun records(programId: String, limit: Int): JsonObject = runBlocking {
        buildJsonObject {
            put("runs", JsonArray(platform.dao.runs(programId, limit).map { run ->
                buildJsonObject {
                    put("runId", run.runId); put("trigger", run.trigger); put("dryRun", run.dryRun); put("queuedAt", run.queuedAt)
                    put("outcome", run.outcome); put("reason", run.reason); run.exitCode?.let { put("exitCode", it) }
                    run.signal?.let { put("signal", it) }; if (run.droppedEvents > 0) put("droppedEvents", run.droppedEvents)
                }
            }))
            put("calls", JsonArray(platform.dao.operations(programId, limit).map { op ->
                buildJsonObject {
                    put("capability", op.capability); put("caller", op.caller); put("status", op.status); put("effect", op.effect)
                    op.errorCode?.let { put("error", it) }; op.errorReason?.let { put("reason", it) }
                }
            }))
            put("logs", JsonArray(platform.dao.logs(programId, limit).map { buildJsonObject { put("at", it.at); put("message", it.message); it.dataJson?.let { d -> put("data", d) } } }))
        }
    }

    /** Only direct children of the workspace programs directory; no links or escapes. */
    internal fun resolve(path: String): File {
        val root = platform.workspacePrograms.canonicalFile
        val trimmed = path.trim().removeSuffix("/")
        val name = when {
            trimmed.startsWith("/") -> File(trimmed).canonicalFile.takeIf { it.parentFile == root }?.name
            else -> trimmed.removePrefix("~/").removePrefix("workspace/").removePrefix("programs/").takeIf { '/' !in it }
        } ?: throw IllegalArgumentException("Programs must be directories directly under ~/workspace/programs")
        if (!name.matches(ProgramPackageReader.PROGRAM_ID) && !name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) throw IllegalArgumentException("Invalid program directory name")
        val dir = File(root, name)
        if (java.nio.file.Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory) throw IllegalArgumentException("Program directory not found: ~/workspace/programs/$name")
        return dir
    }

    /** Direct Node access bypasses mobby's permissions; it is pointed out, not prevented. */
    private fun warnings(dir: File, background: String?): List<String> {
        if (background == null) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension in setOf("js", "cjs", "mjs") && !it.path.contains("/ui/") }.flatMap { file ->
            val text = file.readText()
            BYPASS.filter { (pattern, _) -> pattern.containsMatchIn(text) }.map { (_, what) -> "${file.relativeTo(dir)}: uses $what directly; use the mobby SDK so permissions, records and quotas apply" }
        }.toList()
    }

    private companion object {
        val SCRIPTS = setOf("js", "mjs", "cjs")
        /** Must match the line runner.cjs prints. */
        const val HANDLERS = "mobby:handlers "
        val BYPASS = listOf(
            Regex("""require\(\s*['"](node:)?(http|https|net|tls|dgram)['"]""") to "Node networking",
            Regex("""(?<![.\w])fetch\s*\(""") to "global fetch",
            Regex("""require\(\s*['"](node:)?(fs|fs/promises)['"]""") to "the file system",
            Regex("""require\(\s*['"]node:sqlite['"]""") to "node:sqlite",
            Regex("""require\(\s*['"](node:)?child_process['"]""") to "child processes",
        )
    }
}
