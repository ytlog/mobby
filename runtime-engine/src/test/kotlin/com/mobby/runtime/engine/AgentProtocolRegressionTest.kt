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
            assertEquals(prompt, args.last()); assertEquals("--", args[args.lastIndex - 1])
            assertFalse(args.windowed(2).contains(listOf("-c", prompt)))
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
    @Test fun imageArgumentsUseNativeProtocolAndKeepResumeWithoutShellParsing() {
        for (session in listOf(null, SessionRef("session-1"))) {
            val request = RunRequest(RequestId("r"), AgentId.CODEX, WorkspaceRef("default"), emptyList(), "model", GatewayProfileRef("g", 0), sessionRef = session)
            val image = "/private/image with 'quotes'.png"
            val args = AgentCommand.arguments(request, "/agent", "look", listOf(image))
            assertEquals(image, args[args.indexOf("--image") + 1])
            assertEquals(listOf("--", "look"), args.takeLast(2))
            assertEquals(session != null, "resume" in args)
            val claude = AgentCommand.arguments(request.copy(agentId = AgentId.CLAUDE_CODE), "/agent", "private prompt", streamInput = true)
            assertFalse(claude.contains("private prompt"))
            assertEquals("stream-json", claude[claude.indexOf("--input-format") + 1])
            assertEquals(session != null, "--resume" in claude)
        }
    }

    @Test fun replayedUserImageDoesNotBecomeAnOversizedBase64Diagnostic() {
        val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode("""{"type":"user","message":{"content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"private-image-bytes"}}]}}""")
        assertFalse(facts.toString().contains("private-image-bytes"))
        assertTrue(facts.filterIsInstance<AgentFact.Diagnostic>().any { it.kind == "image" })
        val truncated = ProtocolDecoder(AgentId.CLAUDE_CODE).decode("""{"type":"user","message":{"content":[{"type":"image","source":{"data":"private-image-bytes [line truncated]""")
        assertFalse(truncated.toString().contains("private-image-bytes"))
    }

}
