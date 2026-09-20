package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

/** Regression cases retained from the original console adapter when moving to structured facts. */
class AgentProtocolRegressionTest {
    @Test fun promptIsOneLiteralArgumentEvenWithShellSyntax() {
        val prompt = "--version; $(touch /tmp/unwanted)\n'你好'"
        for (agent in AgentId.values()) {
            val request = RunRequest(RequestId("r"), agent, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0))
            val args = AgentCommand.arguments(request, "/test/agent", prompt)
            assertEquals(prompt, args.last()); assertEquals("--", args[args.lastIndex - 1]); assertFalse(args.contains("-c"))
        }
    }
    @Test fun codexMessageAndFailureAreDecoded() {
        assertEquals("你好", ProtocolDecoder(AgentId.CODEX).decode("""{"type":"item.completed","item":{"type":"agent_message","text":"你好"}}""").filterIsInstance<AgentFact.Text>().single().text)
        assertFalse(ProtocolDecoder(AgentId.CODEX).decode("""{"type":"turn.failed","error":{"message":"auth required"}}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun claudeErrorOverridesSuccessfulExit() {
        val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode("""{"type":"result","is_error":true,"result":"denied"}""")
        assertFalse(facts.filterIsInstance<AgentFact.Completed>().single().success)
        assertEquals("denied", facts.filterIsInstance<AgentFact.Text>().single().text)
    }
    @Test fun malformedUnknownAndNonObjectEventsRemainVisible() {
        for (line in listOf("{incomplete", "[]", "null", """{"type":"future.event","value":42}""")) {
            assertEquals(line, ProtocolDecoder(AgentId.CODEX).decode(line).filterIsInstance<AgentFact.Diagnostic>().single().text)
        }
    }
    @Test fun claudeContentSupportsMultipleTextBlocks() {
        assertEquals("one\ntwo", ProtocolDecoder(AgentId.CLAUDE_CODE).decode(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"one"},{"type":"tool_use"},{"type":"text","text":"two"}]}}""").filterIsInstance<AgentFact.Text>().single().text)
    }
}
