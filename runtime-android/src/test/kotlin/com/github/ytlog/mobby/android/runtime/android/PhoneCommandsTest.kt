package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.engine.SkillDocument
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PhoneCommandsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `token mismatch and oversized arguments are rejected`() {
        val snapshot = PhoneCommands.handle("""{"token":"ok","action":"snapshot"}""", "ok") { _, _ -> "tree" }
        assertTrue(JSONObject(snapshot).getBoolean("ok"))
        assertEquals("tree", JSONObject(snapshot).getString("result"))
        val denied = JSONObject(PhoneCommands.handle("""{"token":"other","action":"snapshot"}""", "ok") { _, _ -> error("should not run") })
        assertFalse(denied.getBoolean("ok"))
        val longQuery = "x".repeat(201)
        val oversized = JSONObject(PhoneCommands.handle("""{"token":"ok","action":"click","query":"$longQuery"}""", "ok") { _, _ -> error("should not run") })
        assertFalse(oversized.getBoolean("ok"))
    }

    @Test fun `click passes visible text and operator failures stay unsuccessful`() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val clicked = JSONObject(PhoneCommands.handle("""{"token":"ok","action":"click","query":"确定"}""", "ok") { action, args ->
            seen += action to args; "已点击：确定"
        })
        assertTrue(clicked.getBoolean("ok"))
        assertEquals("click" to mapOf("query" to "确定"), seen.single())
        val failed = JSONObject(PhoneCommands.handle("""{"token":"ok","action":"type"}""", "ok") { _, _ -> error("请提供要输入的文字") })
        assertFalse(failed.getBoolean("ok"))
        assertEquals("请提供要输入的文字", failed.getString("error"))
    }

    @Test fun `plugin package is a skill bundle without MCP and helper stays on localhost`() {
        val root = temporary.newFolder()
        val skill = PhonePlugin.write(root, "/bin/node", 43123, """tok"en""")
        val preview = SkillDocument.preview(skill.readText())
        assertTrue(preview.issues.isEmpty())
        assertEquals(PhonePlugin.SKILL, preview.name)
        assertEquals(skill, File(root, "skills/${PhonePlugin.SKILL}/SKILL.md"))
        assertTrue(skill.readText().contains("/bin/node"))
        assertTrue(skill.readText().contains("snapshot"))
        assertFalse(skill.readText().contains("tok"))
        val helper = File(root, "skills/${PhonePlugin.SKILL}/scripts/phone.cjs").readText()
        assertTrue(helper.contains("127.0.0.1"))
        assertTrue(helper.contains("43123"))
        assertTrue(helper.contains("""TOKEN="tok\"en""""))
        assertFalse(helper.contains("0.0.0.0"))
        assertFalse(helper.contains("tools/list"))
        val portable = JSONObject(File(root, "plugin.json").readText())
        assertEquals(PhonePlugin.SKILL, portable.getString("name"))
        assertFalse(portable.has("mcpServers"))
        val codex = JSONObject(File(root, ".codex-plugin/plugin.json").readText())
        assertEquals("./skills/", codex.getString("skills"))
        assertFalse(codex.has("mcpServers"))
        val claude = JSONObject(File(root, ".claude-plugin/plugin.json").readText())
        assertEquals(PhonePlugin.SKILL, claude.getString("name"))
        assertFalse(claude.has("mcpServers"))
    }

    @Test fun `skill helper snapshot stays on the localhost token`() {
        val token = "secret-token"
        val phone = PhoneCommandServer(token) { action, _ ->
            assertEquals("snapshot", action)
            "Launcher"
        }
        val helper = PhoneCommandServer.helper(temporary.newFolder(), phone.port, token)
        val proc = ProcessBuilder("node", helper.absolutePath, "snapshot").redirectErrorStream(true).start()
        try {
            assertTrue(proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0, proc.exitValue())
            assertEquals("Launcher\n", proc.inputStream.bufferedReader().readText())
            assertFalse(helper.readText().contains("0.0.0.0"))
        } finally {
            proc.destroyForcibly()
            phone.close()
        }
    }
}
