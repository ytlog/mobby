package com.mdoer.app

import org.junit.Assert.*
import org.junit.Test

class AgentAdapterTest {
    @Test fun promptIsOneLiteralArgumentEvenWithShellSyntax() {
        val prompt = "--version; $(touch /tmp/unwanted)\n'你好'"
        for (mode in listOf(AgentMode.CLAUDE, AgentMode.CODEX)) {
            val args = AgentAdapter.arguments(mode, "/test/agent", prompt)
            assertEquals(prompt, args.last())
            assertEquals("--", args[args.lastIndex - 1])
            assertFalse(args.contains("-c"))
        }
    }
    @Test fun codexMessageAndFailureAreDecoded() {
        assertEquals("你好", AgentAdapter.parse(AgentMode.CODEX, """{"type":"item.completed","item":{"type":"agent_message","text":"你好"}}""").text)
        assertTrue(AgentAdapter.parse(AgentMode.CODEX, """{"type":"turn.failed","error":{"message":"auth required"}}""").error)
    }
    @Test fun claudeErrorOverridesSuccessfulExit() {
        val result = AgentAdapter.parse(AgentMode.CLAUDE, """{"type":"result","is_error":true,"result":"denied"}""")
        assertTrue(result.error)
        assertEquals("denied", result.text)
    }
    @Test fun malformedUnknownAndNonObjectEventsRemainVisible() {
        for (line in listOf("{incomplete", "[]", "null", """{"type":"future.event","value":42}""")) {
            assertEquals(line, AgentAdapter.parse(AgentMode.CODEX, line).text)
        }
    }
    @Test fun claudeContentSupportsMultipleTextBlocks() {
        assertEquals("one\ntwo", AgentAdapter.parse(AgentMode.CLAUDE,
            """{"type":"assistant","message":{"content":[{"type":"text","text":"one"},{"type":"tool_use"},{"type":"text","text":"two"}]}}""").text)
    }
}
