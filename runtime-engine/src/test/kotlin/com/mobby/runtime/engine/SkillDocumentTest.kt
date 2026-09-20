package com.mobby.runtime.engine

import org.junit.Assert.*
import org.junit.Test

class SkillDocumentTest {
    @Test fun `manual skill preserves quoted metadata and markdown body`() {
        val preview = SkillDocument.manual("review", "检查：\"引号\"\n多行", "# 工作流\n读取文件")
        assertTrue(preview.issues.isEmpty())
        assertEquals("检查：\"引号\"\n多行", preview.description)
        assertEquals("# 工作流\n读取文件", preview.body)
    }
    @Test fun `plain markdown retains body for metadata completion`() {
        val preview = SkillDocument.preview("# imported\nInstructions")
        assertEquals("# imported\nInstructions", preview.body)
        assertEquals(2, preview.issues.size)
    }
    @Test fun `unsafe names empty bodies duplicate keys and object tags are rejected`() {
        for (text in listOf("---\nname: ../escape\ndescription: test\n---\nbody", "---\nname: review\ndescription: test\n---\n", "---\nname: review\nname: other\ndescription: test\n---\nbody", "---\nname: !!java.lang.ProcessBuilder [evil]\ndescription: test\n---\nbody")) {
            assertTrue(SkillDocument.preview(text).issues.isNotEmpty())
        }
    }
    @Test fun `oversize and null bytes are rejected before parsing`() {
        for (text in listOf("a".repeat(SkillDocument.MAX_BYTES + 1), "a\u0000b")) assertThrows(IllegalArgumentException::class.java) { SkillDocument.preview(text) }
    }
}
