package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolDecoderTest {
    @Test fun `codex new and resumed runs use the authorized Android app permission boundary`() {
        for (session in listOf(null, SessionRef("session-123"))) {
            val request = RunRequest(RequestId("r"), AgentId.CODEX, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = session)
            val args = AgentCommand.arguments(request, "agent", "read and write the fixture")
            assertTrue(args.windowed(2).contains(listOf("--sandbox", "danger-full-access")))
            assertTrue(args.windowed(2).contains(listOf("-c", "approval_policy=\"never\"")))
            assertTrue(args.indexOf("-c") < args.indexOf("exec"))
            assertTrue(args.indexOf("--sandbox") < args.indexOf("--"))
            if (session != null) assertTrue(args.indexOf("--sandbox") < args.indexOf("resume"))
            assertFalse(args.contains("--ignore-rules"))
        }
    }
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
    @Test fun `approval uses native request identity and full unmodified scope`() {
        val input = """{"command":"printf '%s' 'literal'","unknown":{"preserved":true}}"""
        val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode("""{"type":"control_request","request_id":"request-id","request":{"subtype":"can_use_tool","tool_name":"Bash","input":$input}}""")
        assertEquals(AgentFact.Approval("request-id", "Bash", input), facts.single())
    }
    @Test fun `malformed unknown oversized and truncated control requests cannot authorize partial scope`() {
        val lines = listOf(
            """{"type":"control_request","request_id":broken}""",
            """{"type":"control_request","request_id":1,"request":{"subtype":"can_use_tool","tool_name":"Write","input":{}}}""",
            """{"type":"control_request","request_id":"id","request":{"subtype":"future","tool_name":"Write","input":{}}}""",
            """{"type":"control_request","request_id":"id","request":{"subtype":"can_use_tool","tool_name":"Write","input":"not an object"}}""",
            """{"type":"control_request","request_id":"id","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"content":"${"x".repeat(65537)}"}}}""",
            """{"type":"control_request","request_id":"id","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"content":"${"中".repeat(22000)}"}}}""",
            """{"type":"control_request","request_id":"id" [line truncated]"""
        )
        for (line in lines) {
            val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode(line)
            assertTrue(facts.contains(AgentFact.InvalidApproval))
            assertTrue(facts.none { it is AgentFact.Approval || it is AgentFact.Completed && it.success })
        }
    }
    @Test fun `approval launch uses native stdio with no prompt argument and cannot target codex`() {
        val request = RunRequest(RequestId("r"), AgentId.CLAUDE_CODE, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0))
        val args = AgentCommand.arguments(request, "agent", "private prompt", streamInput = true, approvals = true)
        assertTrue(args.windowed(2).contains(listOf("--permission-prompt-tool", "stdio")))
        assertTrue(args.windowed(2).contains(listOf("--input-format", "stream-json")))
        assertFalse(args.contains("private prompt"))
        assertFalse(args.any { "bypass" in it || "skip-permissions" in it })
        assertThrows(IllegalArgumentException::class.java) { AgentCommand.arguments(request.copy(agentId = AgentId.CODEX), "agent", "x", approvals = true) }
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
    @Test fun `phone plugin is not attached as MCP flags`() {
        val request = RunRequest(RequestId("r"), AgentId.CODEX, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0))
        val args = AgentCommand.arguments(request, "/agent", "use the phone")
        assertFalse(args.any { "mcp_servers" in it || it == "--mcp-config" || it.startsWith("mcp__") })
        assertEquals("use the phone", args.last())
        val claude = AgentCommand.arguments(request.copy(agentId = AgentId.CLAUDE_CODE), "/agent", "use the phone")
        assertFalse(claude.contains("--mcp-config"))
        assertFalse(claude.contains("--allowedTools"))
        assertEquals("use the phone", claude.last())
    }
    @Test fun `codex MCP tool calls keep the tool name and result`() {
        val facts = ProtocolDecoder(AgentId.CODEX).decode(
            """{"type":"item.completed","item":{"id":"mcp-1","type":"mcp_tool_call","server":"phone","tool":"click","arguments":{"query":"确定"},"result":"已点击：确定","status":"completed"}}"""
        )
        assertEquals(AgentFact.Tool("mcp-1", "click", """{"query":"确定"}""", "已点击：确定", ToolOutcome.SUCCEEDED), facts.single())
    }
}
