package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolDecoderTest {
    @Test fun `claude result does not repeat assistant and private thinking stays hidden`() {
        val decoder = ProtocolDecoder(AgentId.CLAUDE_CODE)
        val assistant = decoder.decode("""{"type":"assistant","session_id":"session","message":{"id":"m","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"answer"}]}}""")
        assertEquals(listOf(AgentFact.Session("session"), AgentFact.Text("m", "answer")), assistant)
        assertEquals(listOf(AgentFact.Completed(true)), decoder.decode("""{"type":"result","subtype":"success","is_error":false,"result":"answer"}"""))
    }
    @Test fun `permission denial is not success and missing terminal evidence is not success`() {
        for (line in listOf("""{"type":"result","subtype":"success","is_error":false,"permission_denials":[{}]}""", """{"type":"result","result":"answer"}""")) {
            assertFalse(ProtocolDecoder(AgentId.CLAUDE_CODE).decode(line).filterIsInstance<AgentFact.Completed>().single().success)
        }
    }
    @Test fun `codex tools keep output and explicit status`() {
        val facts = ProtocolDecoder(AgentId.CODEX).decode("""{"type":"item.completed","item":{"id":"tool","type":"command_execution","command":"cat file","aggregated_output":"content","exit_code":1}}""")
        assertEquals(AgentFact.Tool("tool", "command_execution", "cat file", "content", ToolOutcome.FAILED), facts.single())
    }
    @Test fun `malformed and unknown events stay diagnostics not success`() {
        for (line in listOf("{invalid", "[]", """{"type":"future"}""", """{"type":"item.completed"}""")) {
            assertTrue(ProtocolDecoder(AgentId.CODEX).decode(line).single() is AgentFact.Diagnostic)
        }
    }
    @Test fun `resume is explicit and prompt remains literal without bypass flags`() {
        for (agent in AgentId.values()) {
            val request = RunRequest(RequestId("r"), agent, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = SessionRef("session-123"))
            val args = AgentCommand.arguments(request, "agent", "--flag; $(command)\ntext")
            assertEquals("--flag; $(command)\ntext", args.last())
            assertEquals("--", args[args.lastIndex - 1])
            assertTrue(args.contains("session-123"))
            assertFalse(args.any { "bypass" in it || "skip-permissions" in it || it == "--last" })
        }
    }
}
