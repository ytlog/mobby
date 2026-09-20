package com.mobby.runtime.engine

import com.mobby.runtime.api.SkillPreview
import com.mobby.runtime.api.SkillIssue
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Parse YAML as data, with duplicate keys, aliases and object construction disabled. */
object SkillDocument {
    const val MAX_BYTES = 128 * 1024
    fun preview(input: String): SkillPreview {
        require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && '\u0000' !in input)
        val text = input.removePrefix("\uFEFF").replace("\r\n", "\n")
        var body = text
        var name = ""
        var description = ""
        val issues = mutableListOf<SkillIssue>()
        if (text.startsWith("---\n")) {
            val lines = text.lines()
            val end = (1 until lines.size).firstOrNull { lines[it] == "---" }
            if (end == null) issues += SkillIssue.UNCLOSED_FRONTMATTER
            else {
                body = lines.drop(end + 1).joinToString("\n").trim()
                try {
                    val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 0; codePointLimit = MAX_BYTES; nestingDepthLimit = 20 }
                    val metadata = Yaml(SafeConstructor(options)).load<Any?>(lines.subList(1, end).joinToString("\n")) as? Map<*, *>
                    if (metadata == null) issues += SkillIssue.INVALID_FRONTMATTER
                    else {
                        name = metadata["name"] as? String ?: ""
                        description = metadata["description"] as? String ?: ""
                    }
                } catch (_: Exception) { issues += SkillIssue.INVALID_FRONTMATTER }
            }
        }
        if (!name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) || name.length > 64 || name == "synced") issues += SkillIssue.INVALID_NAME
        if (description.isBlank() || description.length > 1024) issues += SkillIssue.INVALID_DESCRIPTION
        if (body.isBlank()) issues += SkillIssue.EMPTY_BODY
        return SkillPreview(name, description, body, text, issues)
    }
    fun manual(name: String, description: String, body: String): SkillPreview = preview(
        "---\nname: ${JsonPrimitive(name.trim())}\ndescription: ${JsonPrimitive(description.trim())}\n---\n\n${body.trim()}\n")
}
