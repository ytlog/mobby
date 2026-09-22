package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.SkillDocument
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(name: String = "review", body: String = "Review files") = SkillDocument.manual(name, "Use for reviewing", body).markdown
    @Test fun `saved skill is CLI discoverable and survives new store instance`() {
        val home = temporary.newFolder()
        val saved = SkillStore(home).save(AgentId.CODEX, document())
        assertEquals(document(), File(home, ".agents/skills/review/SKILL.md").readText())
        assertEquals(saved, SkillStore(home).list(AgentId.CODEX).single())
        assertTrue(SkillStore(home).prompt(AgentId.CODEX, setOf(saved.ref), "request").contains("$" + "review"))
    }
    @Test fun `Claude selected skills use native Skill tool rather than a generic file read`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val saved = store.save(AgentId.CLAUDE_CODE, document())
        assertTrue(store.prompt(AgentId.CLAUDE_CODE, setOf(saved.ref), "request").contains("Skill 工具"))
    }
    @Test fun `Codex creator draft maps to native dollar invocation without changing unrelated text`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val saved = store.save(AgentId.CODEX, document("skill-creator"))
        val prompt = store.prompt(AgentId.CODEX, setOf(saved.ref), "请用 /skill-creator 帮我创建技能，要求是：review")
        assertTrue(prompt.endsWith("请用 $" + "skill-creator 帮我创建技能，要求是：review"))
        assertEquals("/skill-creator", store.prompt(AgentId.CODEX, emptySet(), "/skill-creator"))
    }
    @Test fun `same name never overwrites and changed file invalidates old reference`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val saved = store.save(AgentId.CLAUDE_CODE, document())
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { store.save(AgentId.CLAUDE_CODE, document(body = "different")) }
        assertEquals("Review files", store.preview(saved.ref)!!.body)
        File(home, ".claude/skills/review/SKILL.md").writeText(document(body = "changed"))
        assertNull(store.resolve(saved.ref, AgentId.CLAUDE_CODE))
        assertNull(store.resolve(saved.ref, AgentId.CODEX))
    }
    @Test fun `symlink roots cannot write outside controlled HOME`() {
        val home = temporary.newFolder(); val outside = temporary.newFolder()
        Files.createSymbolicLink(File(home, ".agents").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { SkillStore(home).save(AgentId.CODEX, document()) }
        assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun `preview does not execute content and invalid import creates no directory`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        assertThrows(IllegalArgumentException::class.java) { store.save(AgentId.CODEX, "run shell now") }
        assertTrue(home.listFiles()!!.isEmpty())
        val saved = store.save(AgentId.CLAUDE_CODE, document(body = "!`touch should-not-exist`"))
        assertTrue(store.prompt(AgentId.CLAUDE_CODE, setOf(saved.ref), "request").contains("/review"))
        assertFalse(File(home, "should-not-exist").exists())
    }
    @Test fun `plugin capability refs are not treated as skills`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val creator = store.save(AgentId.CODEX, document("skill-creator"))
        assertThrows(IllegalArgumentException::class.java) {
            store.prompt(AgentId.CODEX, setOf(CapabilityRef(PhonePlugin.REF)), "request")
        }
        assertFalse(store.hasCreator(AgentId.CODEX, setOf(CapabilityRef(PhonePlugin.REF))))
        assertTrue(store.hasCreator(AgentId.CODEX, setOf(creator.ref, CapabilityRef(PhonePlugin.REF))))
    }
    @Test fun `plugin skill is staged into the agent skill root and inlined for both CLIs`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val authored = PhonePlugin.write(temporary.newFolder(), "/bin/node", 9, "tok")
        val staged = store.stage(AgentId.CODEX, PhonePlugin.SKILL, authored)
        assertEquals(File(home, ".agents/skills/${PhonePlugin.SKILL}/SKILL.md"), staged)
        assertTrue(File(home, ".agents/skills/${PhonePlugin.SKILL}/scripts/phone.cjs").isFile)
        assertTrue(store.list(AgentId.CODEX).none { it.name == PhonePlugin.SKILL })
        val codex = store.prompt(AgentId.CODEX, emptySet(), "open settings", extras = listOf(PhonePlugin.SKILL to staged!!))
        assertTrue(codex.contains("$" + PhonePlugin.SKILL))
        assertTrue(codex.contains(staged.absolutePath))
        assertTrue(codex.contains("snapshot"))
        store.unstage(AgentId.CODEX, PhonePlugin.SKILL)
        assertFalse(File(home, ".agents/skills/${PhonePlugin.SKILL}").exists())
        val claudeFile = store.stage(AgentId.CLAUDE_CODE, PhonePlugin.SKILL, authored)!!
        val claude = store.prompt(AgentId.CLAUDE_CODE, emptySet(), "open settings", extras = listOf(PhonePlugin.SKILL to claudeFile))
        assertTrue(claude.contains("/" + PhonePlugin.SKILL))
        assertTrue(claude.contains("Skill 工具"))
        assertTrue(claude.contains("/bin/node"))
        store.unstage(AgentId.CLAUDE_CODE, PhonePlugin.SKILL)
    }
    @Test fun `staging does not replace a user skill of the same name`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        store.save(AgentId.CLAUDE_CODE, document(PhonePlugin.SKILL, "keep this user skill"))
        val authored = PhonePlugin.write(temporary.newFolder(), "/bin/node", 9, "tok")
        assertNull(store.stage(AgentId.CLAUDE_CODE, PhonePlugin.SKILL, authored))
        assertEquals("keep this user skill", SkillDocument.preview(File(home, ".claude/skills/${PhonePlugin.SKILL}/SKILL.md").readText()).body)
    }
}
