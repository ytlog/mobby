package com.mobby.runtime.android

import com.mobby.runtime.api.*
import com.mobby.runtime.engine.SkillDocument
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/** Only these CLI-discoverable roots are writable. Names and references never become arbitrary paths. */
internal class SkillStore(private val home: File) {
    private data class Root(val agent: AgentId, val relative: String, val source: SkillSource, val writable: Boolean)
    private val roots = listOf(Root(AgentId.CODEX, ".agents/skills", SkillSource.USER, true),
        Root(AgentId.CLAUDE_CODE, ".claude/skills", SkillSource.USER, true),
        Root(AgentId.CODEX, ".codex/skills/.system", SkillSource.BUILTIN, false))
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
        directory.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name }?.map { folder ->
            val file = File(folder, "SKILL.md")
            val preview = runCatching { SkillDocument.preview(read(file)) }.getOrNull()
            val valid = preview != null && preview.issues.isEmpty() && preview.name == folder.name
            val summary = SkillSummary(ref(root, folder.name, preview?.markdown.orEmpty()), agent, preview?.name?.ifBlank { folder.name } ?: folder.name,
                preview?.description.orEmpty(), root.source, valid, if (valid) null else RuntimeError(ErrorCode.INVALID_CONFIG))
            summary to file
        }.orEmpty()
    }
    fun list(agent: AgentId): List<SkillSummary> = entries(agent).map { it.first }
    fun resolve(ref: CapabilityRef, agent: AgentId): File? = entries(agent).firstOrNull { it.first.ref == ref && it.first.available }?.second
    fun preview(ref: CapabilityRef): SkillPreview? {
        val file = AgentId.values().firstNotNullOfOrNull { resolve(ref, it) } ?: return null
        return SkillDocument.preview(read(file))
    }
    @Synchronized fun save(agent: AgentId, markdown: String): SkillSummary {
        val preview = SkillDocument.preview(markdown)
        require(preview.issues.isEmpty())
        if (list(agent).any { it.name == preview.name }) throw java.nio.file.FileAlreadyExistsException(preview.name)
        val root = roots.single { it.agent == agent && it.writable }
        val directory = File(home, root.relative)
        require(safe(directory))
        Files.createDirectories(directory.toPath())
        val target = File(directory, preview.name)
        require(safe(target))
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(preview.name)
        val temporary = File(directory, ".import-${UUID.randomUUID()}")
        Files.createDirectory(temporary.toPath())
        try {
            val document = File(temporary, "SKILL.md")
            java.io.FileOutputStream(document).use { it.write(preview.markdown.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath()) // no replacement; a conflict keeps the existing skill intact
        } finally { File(temporary, "SKILL.md").delete(); temporary.delete() }
        return list(agent).single { it.name == preview.name && it.source == root.source }
    }
    fun prompt(agent: AgentId, refs: Set<CapabilityRef>, text: String): String {
        if (refs.isEmpty()) return text
        val selectedNames = mutableSetOf<String>()
        val selections = refs.map { ref ->
            val file = resolve(ref, agent) ?: throw IllegalArgumentException("Skill unavailable")
            val preview = SkillDocument.preview(read(file))
            selectedNames += preview.name
            val invocation = if (agent == AgentId.CODEX) "$" + preview.name else "/" + preview.name
            "$invocation — ${file.absolutePath}"
        }
        val instruction = if (agent == AgentId.CLAUDE_CODE) "请使用 Skill 工具调用下列已选择的技能，遵守 Agent 权限检查："
            else "请使用下列已选择的技能，读取对应 SKILL.md 并遵守 Agent 权限检查："
        val input = if (agent == AgentId.CODEX && "skill-creator" in selectedNames && text.startsWith("请用 /skill-creator 帮我创建技能，要求是："))
            text.replaceFirst("/skill-creator", "$" + "skill-creator") else text
        return instruction + "\n" + selections.joinToString("\n") + "\n\n" + input
    }
}
