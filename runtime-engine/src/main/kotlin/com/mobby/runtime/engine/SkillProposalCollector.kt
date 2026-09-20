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
            var body = StringBuilder()
            for (line in lines) {
                if (fence == null) {
                    val opening = Regex("^(`{4,})SKILL\\.md[ \\t]*$").matchEntire(line)
                    if (opening != null) { fence = opening.groupValues[1]; body = StringBuilder() }
                } else if (line.trimEnd().length >= fence.length && line.trimEnd().all { it == '`' }) {
                    val content = body.toString()
                    if (content.isNotBlank() && content.toByteArray().size <= 128 * 1024) result += content
                    fence = null
                } else body.append(line).append('\n')
            }
        }
        return result.distinct().take(4)
    }
}
