package com.mobby.runtime.engine

/** Only explicitly tagged public assistant output can become a proposal. Never inspect tool logs. */
internal class SkillProposalCollector {
    private val messages = linkedMapOf<String, StringBuilder>()
    private var bytes = 0
    private var overflow = false
    fun append(messageId: String, text: String) {
        if (overflow) return
        bytes += text.toByteArray().size
        if (bytes > 512 * 1024 || messageId !in messages && messages.size >= 64) { overflow = true; messages.clear(); return }
        messages.getOrPut(messageId) { StringBuilder() }.append(text)
    }
    fun complete(): List<String> {
        if (overflow) return emptyList()
        val result = mutableListOf<String>()
        for (message in messages.values) {
            val lines = message.toString().replace("\r\n", "\n").lines()
            var fence: String? = null
            var proposal = false
            var body = StringBuilder()
            for (line in lines) {
                val current = fence
                if (current == null) {
                    val opening = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line) ?: continue
                    val delimiter = opening.groupValues[1]
                    val info = opening.groupValues[2]
                    // Backticks in the info string invalidate a backtick fence.
                    if (delimiter.first() == '`' && '`' in info) continue
                    fence = delimiter
                    proposal = info.trim() == "SKILL.md"
                    body = StringBuilder()
                } else {
                    val trimmed = line.trimStart(' ')
                    val closing = trimmed.trimEnd(' ', '\t')
                    if (line.length - trimmed.length <= 3 && closing.length >= current.length && closing.all { it == current.first() }) {
                        val content = body.toString()
                        if (proposal && content.isNotBlank() && content.toByteArray().size <= 128 * 1024) result += content
                        fence = null
                    } else if (proposal) body.append(line).append('\n')
                }
            }
        }
        return result.distinct().take(4)
    }
}
