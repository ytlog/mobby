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
}
