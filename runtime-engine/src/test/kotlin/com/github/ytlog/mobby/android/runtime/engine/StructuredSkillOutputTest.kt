package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StructuredSkillOutputTest {
    private val draft = """{"kind":"proposal","message":"Ready","name":"fixture","description":"Review changes","body":"Read the diff."}"""
    private fun item(text: String) = buildJsonObject {
        put("type", "item.completed")
        putJsonObject("item") { put("id", "final"); put("type", "agent_message"); put("text", text) }
    }.toString()
    @Test fun `missing structured result cannot succeed even with CLI success`() {
        val codex = ProtocolDecoder(AgentId.CODEX, RequestedOutput.SKILL_PROPOSAL)
        codex.decode(item("ordinary reply"))
        assertFalse(codex.decode("""{"type":"turn.completed"}""").filterIsInstance<AgentFact.Completed>().single().success)
        val claude = ProtocolDecoder(AgentId.CLAUDE_CODE, RequestedOutput.SKILL_PROPOSAL)
        assertFalse(claude.decode("""{"type":"result","subtype":"success","is_error":false,"result":"ordinary reply"}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun `structured Codex response exposes readable message rather than JSON`() {
        val decoder = ProtocolDecoder(AgentId.CODEX, RequestedOutput.SKILL_PROPOSAL)
        val facts = decoder.decode(item(draft))
        assertEquals("Ready", facts.filterIsInstance<AgentFact.Text>().single().text)
        assertTrue(decoder.decode("""{"type":"turn.completed"}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun `malformed structured final response invalidates earlier candidate`() {
        val decoder = ProtocolDecoder(AgentId.CODEX, RequestedOutput.SKILL_PROPOSAL)
        decoder.decode(item(draft))
        decoder.decode(item("""{"kind":"proposal","message":"Bad","name":"../bad","description":"Test","body":"Body"}"""))
        assertFalse(decoder.decode("""{"type":"turn.completed"}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun `Markdown wrapped JSON is not a valid native structured response`() {
        val decoder = ProtocolDecoder(AgentId.CODEX, RequestedOutput.SKILL_PROPOSAL)
        decoder.decode(item("```json\n$draft\n```"))
        val terminal = decoder.decode("""{"type":"turn.completed"}""")
        assertFalse(terminal.filterIsInstance<AgentFact.Completed>().single().success)
        assertTrue(terminal.none { it is AgentFact.Proposal })
    }
    @Test fun `Claude only publishes validated structured result from a successful final event`() {
        fun result(success: Boolean) = buildJsonObject {
            put("type", "result"); put("subtype", if (success) "success" else "error_during_execution")
            put("is_error", !success); put("structured_output", Json.parseToJsonElement(draft))
        }.toString()
        for (success in listOf(true, false)) {
            val facts = ProtocolDecoder(AgentId.CLAUDE_CODE, RequestedOutput.SKILL_PROPOSAL).decode(result(success))
            assertEquals(success, facts.filterIsInstance<AgentFact.Completed>().single().success)
            assertEquals(if (success) 1 else 0, facts.filterIsInstance<AgentFact.Proposal>().size)
            if (success) assertEquals(SkillDocument.manual("fixture", "Review changes", "Read the diff.").markdown, facts.filterIsInstance<AgentFact.Proposal>().single().markdown)
        }
    }
    @Test fun `clarification is a successful readable response without a proposal`() {
        val clarification = """{"kind":"clarification","message":"Which files?","name":"","description":"","body":""}"""
        val decoder = ProtocolDecoder(AgentId.CODEX, RequestedOutput.SKILL_PROPOSAL)
        assertEquals("Which files?", decoder.decode(item(clarification)).filterIsInstance<AgentFact.Text>().single().text)
        val terminal = decoder.decode("""{"type":"turn.completed"}""")
        assertTrue(terminal.filterIsInstance<AgentFact.Completed>().single().success)
        assertTrue(terminal.none { it is AgentFact.Proposal })
    }
    @Test fun `schema enforces types limits and no fields outside the contract`() {
        for (text in listOf("{}", draft.replace("\"Ready\"", "null"), draft.replace("\"proposal\"", "\"clarification\""),
            draft.dropLast(1) + ",\"extra\":true}", draft.replace("Read the diff.", "x".repeat(130 * 1024)))) {
            assertNull(SkillGeneration.parse(text))
        }
        val parsed = requireNotNull(SkillGeneration.parse(draft))
        assertTrue(SkillDocument.preview(requireNotNull(parsed.markdown)).issues.isEmpty())
        assertNull(SkillGeneration.parse("````SKILL.md\n---\nname: old\n````"))
    }
    @Test fun `Claude internal output tool does not leak schema machinery as execution steps`() {
        val decoder = ProtocolDecoder(AgentId.CLAUDE_CODE, RequestedOutput.SKILL_PROPOSAL)
        val calls = decoder.decode("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"format","name":"StructuredOutput","input":{}},{"type":"tool_use","id":"read","name":"Read","input":{}}]}}""")
        assertEquals(listOf("read"), calls.filterIsInstance<AgentFact.Tool>().map { it.id })
        assertTrue(decoder.decode("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"format","content":"ok"}]}}""").isEmpty())
    }
    @Test fun `native schema flags are present on both first and resumed skill requests only`() {
        for (agent in AgentId.values()) for (session in listOf(null, SessionRef("session"))) {
            val request = RunRequest(RequestId("r"), agent, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = session, requestedOutput = RequestedOutput.SKILL_PROPOSAL)
            val args = AgentCommand.arguments(request, "/agent", "prompt", streamInput = agent == AgentId.CLAUDE_CODE, approvals = agent == AgentId.CLAUDE_CODE, schemaPath = if (agent == AgentId.CODEX) "/private/schema.json" else null)
            if (agent == AgentId.CLAUDE_CODE) assertEquals(SkillGeneration.schema, args[args.indexOf("--json-schema") + 1])
            else { assertFalse(args.contains("--output-schema")); assertFalse(args.contains("/private/schema.json")); assertTrue(args.contains("app-server")) }
            assertFalse("--json-schema" in AgentCommand.arguments(request.copy(requestedOutput = RequestedOutput.TEXT), "/agent", "prompt"))
            assertFalse("--output-schema" in AgentCommand.arguments(request.copy(requestedOutput = RequestedOutput.TEXT), "/agent", "prompt"))
        }
    }

}
