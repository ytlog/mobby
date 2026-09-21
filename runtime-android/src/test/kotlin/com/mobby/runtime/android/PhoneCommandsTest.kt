package com.mobby.runtime.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    @Test fun `helper script stays on localhost and embeds the escaped token`() {
        val file = PhoneCommandServer.helper(temporary.newFolder(), 43123, """tok"en""")
        val text = file.readText()
        assertTrue(text.contains("127.0.0.1"))
        assertTrue(text.contains("43123"))
        assertTrue(text.contains("""token:"tok\"en""""))
        assertFalse(text.contains("0.0.0.0"))
    }
}
