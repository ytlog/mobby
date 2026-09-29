package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.device.DeviceSkillPack
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
    @Test fun `Pi shares existing skills after upgrade without overwriting conflicts`() {
        for (conflict in listOf(false, true)) {
            val home = temporary.newFolder(); val store = SkillStore(home)
            for (root in listOf(".agents/skills", ".claude/skills", ".config/opencode/skills")) {
                val folder = File(home, "$root/review").apply { mkdirs() }
                File(folder, "SKILL.md").writeText(document())
                File(folder, ".mobby-shared").writeText("mobby")
                File(folder, "scripts/check.sh").apply { parentFile.mkdirs(); writeText("echo shared-resource"); setExecutable(true) }
            }
            val pi = File(home, ".pi/agent/skills/review/SKILL.md")
            if (conflict) { pi.parentFile.mkdirs(); pi.writeText(document(body = "user content")) }
            store.installBundled(emptyMap())
            assertEquals(document(body = if (conflict) "user content" else "Review files"), pi.readText())
            assertEquals(!conflict, store.list(AgentId.PI).any { it.name == "review" })
            if (!conflict) {
                assertEquals("echo shared-resource", File(pi.parentFile, "scripts/check.sh").readText())
                assertTrue(File(pi.parentFile, "scripts/check.sh").canExecute())
                val selected = store.list(AgentId.PI).single()
                assertTrue(store.prompt(AgentId.PI, setOf(selected.ref), "review").contains("/skill:review"))
            }
            assertEquals(document(), File(home, ".agents/skills/review/SKILL.md").readText())
        }
    }
    @Test fun `Pi upgrade rejects different supporting resources or symlinks`() {
        for (symlink in listOf(false, true)) {
            val home = temporary.newFolder(); val store = SkillStore(home)
            for (root in listOf(".agents/skills", ".claude/skills", ".config/opencode/skills")) {
                val folder = File(home, "$root/review").apply { mkdirs() }
                File(folder, "SKILL.md").writeText(document())
                File(folder, ".mobby-shared").writeText("mobby")
                File(folder, "data.txt").writeText("same")
            }
            val resource = File(home, ".claude/skills/review/data.txt")
            if (symlink) { resource.delete(); Files.createSymbolicLink(resource.toPath(), File(home, ".agents/skills/review/data.txt").toPath()) }
            else resource.writeText("different")
            store.installBundled(emptyMap())
            assertFalse(File(home, ".pi/agent/skills/review").exists())
        }
    }
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
            store.prompt(AgentId.CODEX, setOf(CapabilityRef("plugin:device:screen")), "request")
        }
        assertFalse(store.hasCreator(AgentId.CODEX, setOf(CapabilityRef("plugin:device:screen"))))
        assertTrue(store.hasCreator(AgentId.CODEX, setOf(creator.ref, CapabilityRef("plugin:device:screen"))))
    }
    @Test fun `plugin skill is staged into the agent skill root and referenced by path`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        val authored = DeviceSkillPack.write(temporary.newFolder(), "/bin/node", 9, "tok", setOf("plugin:device:screen")).single()
        val staged = store.stage(AgentId.CODEX, "mobby-screen", authored)
        assertEquals(File(home, ".agents/skills/mobby-screen/SKILL.md"), staged)
        assertTrue(File(home, ".agents/skills/mobby-screen/scripts/device.cjs").isFile)
        assertTrue(store.list(AgentId.CODEX).none { it.name == "mobby-screen" })
        val codex = store.prompt(AgentId.CODEX, emptySet(), "open settings", extras = listOf("mobby-screen" to staged!!))
        assertTrue(codex.contains("$" + "mobby-screen"))
        assertTrue(codex.contains(staged.absolutePath))
        assertFalse(codex.contains("snapshot"))
        assertTrue(staged.readText().contains("snapshot"))
        store.unstage(AgentId.CODEX, "mobby-screen")
        assertFalse(File(home, ".agents/skills/mobby-screen").exists())
        val claudeFile = store.stage(AgentId.CLAUDE_CODE, "mobby-screen", authored)!!
        val claude = store.prompt(AgentId.CLAUDE_CODE, emptySet(), "open settings", extras = listOf("mobby-screen" to claudeFile))
        assertTrue(claude.contains("/mobby-screen"))
        assertTrue(claude.contains("Skill 工具"))
        assertFalse(claude.contains("/bin/node"))
        store.unstage(AgentId.CLAUDE_CODE, "mobby-screen")
        val openCodeFile = store.stage(AgentId.OPEN_CODE, "mobby-screen", authored)!!
        assertEquals(File(home, ".config/opencode/skills/mobby-screen/SKILL.md"), openCodeFile)
        val openCode = store.prompt(AgentId.OPEN_CODE, emptySet(), "open settings", extras = listOf("mobby-screen" to openCodeFile))
        assertTrue(openCode.contains("/mobby-screen"))
        assertTrue(openCode.contains("skill 工具"))
        store.unstage(AgentId.OPEN_CODE, "mobby-screen")
        val piFile = store.stage(AgentId.PI, "mobby-screen", authored)!!
        assertEquals(File(home, ".pi/agent/skills/mobby-screen/SKILL.md"), piFile)
        assertTrue(store.prompt(AgentId.PI, emptySet(), "open settings", extras = listOf("mobby-screen" to piFile)).contains("/skill:mobby-screen"))
        store.unstage(AgentId.PI, "mobby-screen")
    }
    @Test fun `staging does not replace a user skill of the same name`() {
        val home = temporary.newFolder(); val store = SkillStore(home)
        store.save(AgentId.CLAUDE_CODE, document("mobby-screen", "keep this user skill"))
        val authored = DeviceSkillPack.write(temporary.newFolder(), "/bin/node", 9, "tok", setOf("plugin:device:screen")).single()
        assertNull(store.stage(AgentId.CLAUDE_CODE, "mobby-screen", authored))
        assertTrue(store.blocked(AgentId.CLAUDE_CODE, "mobby-screen"))
        assertEquals("keep this user skill", SkillDocument.preview(File(home, ".claude/skills/mobby-screen/SKILL.md").readText()).body)
    }
}
