package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolDecoderTest {
    @Test fun `codex new and resumed runs use the authorized Android app permission boundary`() {
        for (session in listOf(null, SessionRef("session-123"))) {
            val request = RunRequest(RequestId("r"), AgentId.CODEX, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = session)
            val args = AgentCommand.arguments(request, "agent", "read and write the fixture")
            assertTrue(args.windowed(2).contains(listOf("--sandbox", "danger-full-access")))
            assertTrue(args.windowed(2).contains(listOf("-c", "approval_policy=\"never\"")))
            assertTrue(args.indexOf("-c") < args.indexOf("app-server"))
            assertTrue(args.windowed(3).contains(listOf("app-server", "--listen", "stdio://")))
            assertFalse(args.contains("exec"))
            assertFalse(args.contains("resume"))
            assertFalse(args.contains("read and write the fixture"))
            assertFalse(args.contains("--ignore-rules"))
        }
    }
    @Test fun `claude partial text is appended in output order and the later snapshot does not repeat it`() {
        val decoder = ProtocolDecoder(AgentId.CLAUDE_CODE)
        assertTrue(decoder.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m"}}}""").isEmpty())
        assertEquals(listOf(AgentFact.Text("m", "先")), decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"先"}}}"""))
        assertEquals(listOf(AgentFact.Text("m", "截图")), decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"截图"}}}"""))
        assertTrue(decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"private"}}}""").isEmpty())
        assertEquals(listOf(AgentFact.Tool("t1", "snapshot", "snapshot")), decoder.decode("""{"type":"stream_event","event":{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t1","name":"snapshot","input":{}}}}"""))
        assertEquals(listOf(AgentFact.Text("m#3", "再看")), decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"再看"}}}"""))
        val snapshot = decoder.decode("""{"type":"assistant","message":{"id":"m","content":[{"type":"text","text":"先截图"},{"type":"thinking","thinking":"private"},{"type":"tool_use","id":"t1","name":"snapshot","input":{"cmd":"shot"}},{"type":"text","text":"再看"}]}}""")
        assertEquals(listOf(AgentFact.Tool("thinking:m#1", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED), AgentFact.Tool("t1", "snapshot", """{"cmd":"shot"}""")), snapshot)
        assertFalse(snapshot.toString().contains("private"))
        assertEquals(listOf(AgentFact.Completed(true)), decoder.decode("""{"type":"result","subtype":"success","is_error":false,"result":"先截图\n再看"}"""))
    }
    @Test fun `thinking at the first index does not make the later snapshot repeat the paragraph`() {
        val decoder = ProtocolDecoder(AgentId.CLAUDE_CODE)
        assertEquals(listOf(AgentFact.Tool("thinking:m#0", "thinking", "思考")), decoder.decode("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m"}}}""") + decoder.decode("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"private"}}}"""))
        assertTrue(decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private"}}}""").isEmpty())
        assertEquals(listOf(AgentFact.Tool("thinking:m#0", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED), AgentFact.Text("m#1", "answer")), decoder.decode("""{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"answer"}}}"""))
        val snapshot = decoder.decode("""{"type":"assistant","message":{"id":"m","content":[{"type":"text","text":"answer"}]}}""")
        assertTrue(snapshot.none { it is AgentFact.Text })
        assertFalse(snapshot.toString().contains("private"))
        assertEquals(listOf(AgentFact.Completed(true)), decoder.decode("""{"type":"result","subtype":"success","is_error":false,"result":"answer"}"""))
    }
    @Test fun `codex reasoning collapses without its private text`() {
        val decoder = ProtocolDecoder(AgentId.CODEX)
        val started = decoder.decode("""{"type":"item.started","item":{"id":"r","type":"reasoning","text":"private"}}""")
        assertEquals(listOf(AgentFact.Tool("r", "thinking", "思考")), started)
        assertTrue(decoder.decode("""{"type":"item.updated","item":{"id":"r","type":"reasoning","text":"private more"}}""").isEmpty())
        val done = decoder.decode("""{"type":"item.completed","item":{"id":"r","type":"reasoning","text":"private"}}""")
        assertEquals(listOf(AgentFact.Tool("r", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED)), done)
        assertFalse((started + done).toString().contains("private"))
    }
    @Test fun `codex message text streams as a suffix and completion does not repeat it`() {
        val decoder = ProtocolDecoder(AgentId.CODEX)
        assertEquals(listOf(AgentFact.Text("m", "你")), decoder.decode("""{"type":"item.updated","item":{"id":"m","type":"agent_message","text":"你"}}"""))
        assertEquals(listOf(AgentFact.Text("m", "好")), decoder.decode("""{"type":"item.updated","item":{"id":"m","type":"agent_message","text":"你好"}}"""))
        assertTrue(decoder.decode("""{"type":"item.completed","item":{"id":"m","type":"agent_message","text":"你好"}}""").isEmpty())
    }
    @Test fun `claude text stays in block order around tools and thinking stays hidden`() {
        val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode("""{"type":"assistant","message":{"id":"m","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"先截图"},{"type":"tool_use","id":"t1","name":"snapshot","input":{}},{"type":"text","text":"再看结果"}]}}""")
        assertEquals(listOf(AgentFact.Tool("thinking:m#0", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED), AgentFact.Text("m#1", "先截图"), AgentFact.Tool("t1", "snapshot", "{}"), AgentFact.Text("m#3", "再看结果")), facts)
        assertFalse(facts.toString().contains("private"))
    }
    @Test fun `claude result does not repeat assistant and private thinking stays hidden`() {
        val decoder = ProtocolDecoder(AgentId.CLAUDE_CODE)
        val assistant = decoder.decode("""{"type":"assistant","session_id":"session","message":{"id":"m","content":[{"type":"thinking","thinking":"private"},{"type":"text","text":"answer"}]}}""")
        assertEquals(listOf(AgentFact.Session("session"), AgentFact.Tool("thinking:m#0", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED), AgentFact.Text("m#1", "answer")), assistant)
        assertFalse(assistant.toString().contains("private"))
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
        assertTrue(args.contains("--include-partial-messages"))
        assertFalse(args.contains("private prompt"))
        assertFalse(args.any { "bypass" in it || "skip-permissions" in it })
        assertThrows(IllegalArgumentException::class.java) { AgentCommand.arguments(request.copy(agentId = AgentId.CODEX), "agent", "x", approvals = true) }
    }
    @Test fun `resume is explicit and prompt remains literal without bypass flags`() {
        for (agent in AgentId.values()) {
            val request = RunRequest(RequestId("r"), agent, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = SessionRef("session-123"))
            val args = AgentCommand.arguments(request, "agent", "--flag; $(command)\ntext", streamInput = agent == AgentId.CLAUDE_CODE, approvals = agent == AgentId.CLAUDE_CODE)
            when (agent) {
                AgentId.CLAUDE_CODE -> {
                    assertFalse(args.contains("--flag; $(command)\ntext"))
                    assertTrue(args.windowed(2).contains(listOf("--resume", "session-123")))
                }
                AgentId.CODEX -> {
                    assertFalse(args.contains("--flag; $(command)\ntext"))
                    assertFalse(args.contains("session-123"))
                    assertTrue(args.contains("app-server"))
                }
                AgentId.OPEN_CODE -> {
                    assertEquals("--flag; $(command)\ntext", args.last())
                    assertTrue(args.windowed(2).contains(listOf("--session", "session-123")))
                    assertTrue(args.windowed(2).contains(listOf("--format", "json")))
                    assertTrue(args.contains("--auto"))
                    assertFalse(args.any { "dangerously-skip-permissions" in it })
                }
            }
            assertFalse(args.any { "bypass" in it || "skip-permissions" in it || it == "--last" })
        }
    }
    @Test fun `phone plugin is not attached as MCP flags`() {
        val request = RunRequest(RequestId("r"), AgentId.CODEX, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0))
        val args = AgentCommand.arguments(request, "/agent", "use the phone")
        assertFalse(args.any { "mcp_servers" in it || it == "--mcp-config" || it.startsWith("mcp__") })
        assertFalse(args.contains("use the phone"))
        assertTrue(args.contains("app-server"))
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
    @Test fun `opencode json events keep the session, hide reasoning, and finish on stop`() {
        val decoder = ProtocolDecoder(AgentId.OPEN_CODE)
        val text = decoder.decode("""{"type":"text","sessionID":"ses_Ab12","part":{"id":"p1","type":"text","text":"你好"}}""")
        assertEquals(listOf(AgentFact.Session("ses_Ab12"), AgentFact.Text("p1", "你好")), text)
        val thinking = decoder.decode("""{"type":"reasoning","sessionID":"ses_Ab12","part":{"id":"r","type":"reasoning","text":"private"}}""")
        assertEquals(listOf(AgentFact.Session("ses_Ab12"), AgentFact.Tool("r", "thinking", "思考", outcome = ToolOutcome.SUCCEEDED)), thinking)
        assertFalse(thinking.toString().contains("private"))
        val tool = decoder.decode("""{"type":"tool_use","sessionID":"ses_Ab12","part":{"id":"t","type":"tool","tool":"bash","state":{"status":"completed","input":{"cmd":"ls"},"output":"file"}}}""")
        assertEquals(AgentFact.Tool("t", "bash", """{"cmd":"ls"}""", "file", ToolOutcome.SUCCEEDED), tool.filterIsInstance<AgentFact.Tool>().single())
        assertTrue(decoder.decode("""{"type":"step_finish","sessionID":"ses_Ab12","part":{"type":"step-finish","reason":"tool-calls"}}""").none { it is AgentFact.Completed })
        assertTrue(decoder.decode("""{"type":"step_finish","sessionID":"ses_Ab12","part":{"type":"step-finish","reason":"stop"}}""").filterIsInstance<AgentFact.Completed>().single().success)
        assertFalse(decoder.decode("""{"type":"error","sessionID":"ses_Ab12","error":{"name":"Provider"}}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun `opencode resumes by session and attaches image files without skipping permissions`() {
        val image = "/private/shot.png"
        val args = AgentCommand.arguments(
            RunRequest(RequestId("r"), AgentId.OPEN_CODE, WorkspaceRef("default"), emptyList(), "vendor/model", GatewayProfileRef("OPEN_CODE", 1), sessionRef = SessionRef("ses_Ab12")),
            "/agent", "look", listOf(image))
        assertEquals("look", args.last())
        assertTrue(args.windowed(2).contains(listOf("--session", "ses_Ab12")))
        assertTrue(args.windowed(2).contains(listOf("--file", image)))
        assertTrue(args.windowed(2).contains(listOf("-m", "openai/vendor/model")))
        assertTrue(args.contains("--auto"))
        assertFalse(args.any { "skip-permissions" in it || "bypass" in it })
    }
}
