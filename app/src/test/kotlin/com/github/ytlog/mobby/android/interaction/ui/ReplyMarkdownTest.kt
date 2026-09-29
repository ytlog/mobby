package com.github.ytlog.mobby.android.interaction.ui

import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.TableBlock
import org.junit.Assert.*
import org.junit.Test

class ReplyMarkdownTest {
    @Test fun `fences preserve nested code whitespace and tilde delimiters`() {
        val nodes = ReplyMarkdown.children(ReplyMarkdown.parse("~~~~python\n  print(1)\n\n~~~~\n"))
        assertEquals("  print(1)\n\n", (nodes.single() as FencedCodeBlock).literal)
    }
    @Test fun `headings lists quotes and tables become structured blocks`() {
        val nodes = ReplyMarkdown.children(ReplyMarkdown.parse("# Title\n\n3. first\n4. second\n\n> quote\n\n| a | b |\n| - | - |\n| c | d |"))
        assertTrue(nodes[0] is Heading)
        assertEquals(3, (nodes[1] as OrderedList).markerStartNumber)
        assertTrue(nodes[2] is BlockQuote)
        assertTrue(nodes[3] is TableBlock)
    }
    @Test fun `links never launch scripts files or Android intents`() {
        listOf("https://example.com/path", "http://example.com", "mailto:test@example.com").forEach { assertTrue(ReplyMarkdown.safeLink(it)) }
        listOf("javascript:alert(1)", "file:///private/config", "intent://settings", "data:text/html,hello", "https:", "relative.md").forEach { assertFalse(ReplyMarkdown.safeLink(it)) }
    }
}
