package com.mobby.runtime.engine

import org.junit.Assert.*
import org.junit.Test

class SkillProposalCollectorTest {
    @Test fun `split public output preserves nested code and ignores incomplete fences`() {
        val collector = SkillProposalCollector()
        collector.append("one", "````SKILL.")
        collector.append("one", "md\n---\nname: review\n---\n```sh\necho ok\n```\n````")
        collector.append("two", "````SKILL.md\nincomplete")
        assertEquals(listOf("---\nname: review\n---\n```sh\necho ok\n```\n"), collector.complete())
    }
    @Test fun `ordinary replies and overlimit output produce no fake artifact`() {
        val plain = SkillProposalCollector(); plain.append("one", "---\nname: not-an-artifact\n---")
        assertTrue(plain.complete().isEmpty())
        val huge = SkillProposalCollector(); huge.append("one", "````SKILL.md\n" + "a".repeat(512 * 1024) + "\n````")
        assertTrue(huge.complete().isEmpty())
    }
    @Test fun `explicit standard markdown skill fence remains editable even with invalid frontmatter`() {
        val collector = SkillProposalCollector()
        collector.append("one", "```SKILL.md\nname: incomplete\n---\nReview changes.\n```")
        assertEquals(listOf("name: incomplete\n---\nReview changes.\n"), collector.complete())
        assertFalse(SkillDocument.preview(collector.complete().single()).issues.isEmpty())
    }
    @Test fun `skill fence quoted inside a different code block is not a proposal`() {
        val collector = SkillProposalCollector()
        collector.append("one", "`````markdown\n````SKILL.md\nexample only\n````\n`````")
        assertTrue(collector.complete().isEmpty())
    }

    @Test fun `matching fence delimiter keeps embedded code and ignores examples in tilde fences`() {
        val collector = SkillProposalCollector()
        collector.append("example", "~~~text\n```SKILL.md\nexample only\n```\n~~~")
        collector.append("proposal", "  ~~~SKILL.md\nbody\n```sh\necho ok\n```\n  ~~~")
        assertEquals(listOf("body\n```sh\necho ok\n```\n"), collector.complete())
    }

}
