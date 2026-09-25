package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.SkillDocument
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/** Only these CLI-discoverable roots are writable. Names and references never become arbitrary paths. */
internal class SkillStore(private val home: File) {
    private data class Root(val agent: AgentId, val relative: String, val source: SkillSource)
    private val roots = listOf(Root(AgentId.CODEX, ".agents/skills", SkillSource.USER),
        Root(AgentId.CLAUDE_CODE, ".claude/skills", SkillSource.USER),
        Root(AgentId.OPEN_CODE, ".config/opencode/skills", SkillSource.USER))
    private fun safe(file: File): Boolean {
        val base = home.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        if (!path.startsWith(base)) return false
        var current = base
        if (Files.isSymbolicLink(current)) return false
        for (part in base.relativize(path)) { current = current.resolve(part); if (Files.isSymbolicLink(current)) return false }
        return true
    }
    private fun read(file: File): String {
        require(safe(file) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= SkillDocument.MAX_BYTES)
        return file.inputStream().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val size = stream.read(buffer); if (size < 0) break; out.write(buffer, 0, size); require(out.size() <= SkillDocument.MAX_BYTES) }
            val bytes = out.toByteArray()
            require(bytes.size <= SkillDocument.MAX_BYTES)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
    private fun ref(root: Root, name: String, text: String): CapabilityRef {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return CapabilityRef("skill:${root.agent.name}:${root.source.name}:$name:$hash")
    }
    private fun entries(agent: AgentId): List<Pair<SkillSummary, File>> = roots.filter { it.agent == agent }.flatMap { root ->
        val directory = File(home, root.relative)
        if (!safe(directory)) return@flatMap emptyList()
        directory.listFiles()?.filter { folder ->
            !folder.name.startsWith(".") && !File(folder, MARKER).isFile &&
                (Files.isRegularFile(File(folder, BUNDLED_MARKER).toPath(), NOFOLLOW_LINKS) ||
                    Files.isRegularFile(File(folder, SHARED_MARKER).toPath(), NOFOLLOW_LINKS))
        }?.sortedBy { it.name }?.map { folder ->
            val file = File(folder, "SKILL.md")
            val preview = runCatching { SkillDocument.preview(read(file)) }.getOrNull()
            val valid = preview != null && preview.issues.isEmpty() && preview.name == folder.name
            val source = if (Files.isRegularFile(File(folder, BUNDLED_MARKER).toPath(), NOFOLLOW_LINKS)) SkillSource.BUILTIN else root.source
            val summary = SkillSummary(ref(root.copy(source = source), folder.name, preview?.markdown.orEmpty()), agent, preview?.name?.ifBlank { folder.name } ?: folder.name,
                preview?.description.orEmpty(), source, valid, if (valid) null else RuntimeError(ErrorCode.INVALID_CONFIG))
            summary to file
        }.orEmpty()
    }
    fun list(agent: AgentId): List<SkillSummary> {
        val byAgent = AgentId.values().associateWith { entries(it) }
        return byAgent.getValue(agent).map { it.first }.filter { candidate ->
            candidate.available && byAgent.values.all { items -> items.any { (other, _) ->
                other.available && other.name == candidate.name && other.source == candidate.source &&
                    other.ref.value.substringAfterLast(':') == candidate.ref.value.substringAfterLast(':')
            } }
        }
    }
    /** Refresh App-owned skills for each CLI without replacing user-created directories. */
    fun installBundled(documents: Map<String, String>) {
        documents.forEach { (name, markdown) ->
            val preview = SkillDocument.preview(markdown)
            require(preview.issues.isEmpty() && preview.name == name)
        }
        roots.forEach { root ->
            val directory = File(home, root.relative)
            require(safe(directory))
            Files.createDirectories(directory.toPath())
            documents.forEach document@{ (name, markdown) ->
                val target = File(directory, name)
                require(safe(target))
                val marker = File(target, BUNDLED_MARKER)
                require(safe(marker))
                if (target.exists() && !Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS)) return@document
                Files.createDirectories(target.toPath())
                marker.writeText("mobby")
                val document = File(target, "SKILL.md")
                require(safe(document))
                val temporary = File(target, ".SKILL.md.tmp")
                try {
                    temporary.writeText(markdown)
                    Files.move(temporary.toPath(), document.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
        }
    }
    fun resolve(ref: CapabilityRef, agent: AgentId): File? =
        if (list(agent).none { it.ref == ref }) null else entries(agent).firstOrNull { it.first.ref == ref }?.second
    fun preview(ref: CapabilityRef): SkillPreview? {
        val file = AgentId.values().firstNotNullOfOrNull { resolve(ref, it) } ?: return null
        return SkillDocument.preview(read(file))
    }
    @Synchronized fun save(agent: AgentId, markdown: String): SkillSummary {
        val preview = SkillDocument.preview(markdown)
        require(preview.issues.isEmpty())
        val targets = roots.map { root ->
            val directory = File(home, root.relative)
            val target = File(directory, preview.name)
            require(safe(directory) && safe(target))
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(preview.name)
            Files.createDirectories(directory.toPath())
            target
        }
        val temporary = mutableListOf<File>()
        val installed = mutableListOf<File>()
        try {
            targets.forEach { target ->
                val staging = File(target.parentFile, ".import-${UUID.randomUUID()}")
                require(safe(staging))
                Files.createDirectory(staging.toPath())
                temporary += staging
                java.io.FileOutputStream(File(staging, "SKILL.md")).use { it.write(preview.markdown.toByteArray()); it.fd.sync() }
                File(staging, SHARED_MARKER).writeText("mobby")
            }
            targets.zip(temporary).forEach { (target, staging) ->
                Files.move(staging.toPath(), target.toPath()) // never replace another skill
                installed += target
            }
        } catch (e: Exception) {
            installed.forEach { it.deleteRecursively() }
            throw e
        } finally { temporary.forEach { it.deleteRecursively() } }
        return list(agent).single { it.name == preview.name && it.source == SkillSource.USER }
    }
    fun hasCreator(agent: AgentId, refs: Set<CapabilityRef>): Boolean = list(agent).any { it.available && it.name == "skill-creator" && it.ref in refs }
    fun prompt(agent: AgentId, refs: Set<CapabilityRef>, text: String, extras: List<Pair<String, File>> = emptyList()): String {
        extras.forEach { (name, file) ->
            require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && file.isFile && file.length() <= SkillDocument.MAX_BYTES &&
                file.absolutePath.startsWith("/") && '\u0000' !in file.absolutePath)
        }
        if (refs.isEmpty() && extras.isEmpty()) return text
        val selectedNames = mutableSetOf<String>()
        val selections = refs.map { ref ->
            val file = resolve(ref, agent) ?: throw IllegalArgumentException("Skill unavailable")
            val preview = SkillDocument.preview(read(file))
            selectedNames += preview.name
            val invocation = if (agent == AgentId.CODEX) "$" + preview.name else "/" + preview.name
            "$invocation — ${file.absolutePath}"
        } + extras.map { (name, file) ->
            selectedNames += name
            val invocation = if (agent == AgentId.CODEX) "$" + name else "/" + name
            "$invocation — ${file.absolutePath}"
        }
        val instruction = when (agent) {
            AgentId.CLAUDE_CODE -> AppStrings.claudeSkillsPrompt
            AgentId.OPEN_CODE -> AppStrings.openCodeSkillsPrompt
            AgentId.CODEX -> AppStrings.codexSkillsPrompt
        }
        val input = if (agent == AgentId.CODEX && "skill-creator" in selectedNames && AppStrings.isSkillCreationPrompt(text))
            text.replaceFirst("/skill-creator", "$" + "skill-creator") else text
        return buildString {
            append(instruction).append('\n').append(selections.joinToString("\n"))
            append("\n\n").append(input)
        }
    }
    fun blocked(agent: AgentId, name: String): Boolean {
        val root = roots.single { it.agent == agent }
        val target = File(home, "${root.relative}/$name")
        return target.exists() && !File(target, MARKER).isFile
    }
    fun stage(agent: AgentId, name: String, skillFile: File): File? {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && skillFile.isFile && skillFile.name == "SKILL.md")
        val source = skillFile.parentFile ?: return null
        val root = roots.single { it.agent == agent }
        val directory = File(home, root.relative)
        val target = File(directory, name)
        if (!safe(directory) || !safe(target)) return null
        if (target.exists() && !File(target, MARKER).isFile) return null
        target.deleteRecursively()
        Files.createDirectories(target.toPath())
        skillFile.copyTo(File(target, "SKILL.md"))
        val scripts = File(source, "scripts")
        if (scripts.isDirectory) {
            val dest = File(target, "scripts")
            dest.mkdirs()
            scripts.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
        }
        File(target, MARKER).writeText(name)
        return File(target, "SKILL.md").takeIf { it.isFile }
    }
    fun unstage(agent: AgentId, name: String) {
        val root = roots.single { it.agent == agent }
        val target = File(home, "${root.relative}/$name")
        if (safe(target) && File(target, MARKER).isFile) target.deleteRecursively()
    }
    private companion object { const val MARKER = ".mobby-ephemeral"; const val BUNDLED_MARKER = ".mobby-bundled"; const val SHARED_MARKER = ".mobby-shared" }
}
