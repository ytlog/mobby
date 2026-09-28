package com.github.ytlog.mobby.android.runtime.engine

import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiRpcSessionTest {
    @Test fun `RPC accepts images and later turns but completes only at settled`() = runTest {
        val session = PiRpcSession("saved-session")
        val sent = Channel<JsonObject>(20)
        val writer = launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
        val image = TurnImage("image/png", "/private/input.png", "iVBORw==")
        session.submit(AgentTurn(RequestId("first"), "中文\u2028text", listOf(image)))
        runCurrent()
        val state = sent.receive()
        assertEquals("get_state", state["type"]?.jsonPrimitive?.content)
        val facts = session.onStdout("""{"id":"${state["id"]!!.jsonPrimitive.content}","type":"response","command":"get_state","success":true,"data":{"sessionId":"saved-session"}}""")
        assertEquals(listOf(AgentFact.Session("saved-session")), facts.flatMap { ProtocolDecoder(AgentId.PI).decode(it) })
        runCurrent()
        val prompt = sent.receive()
        assertEquals("中文\u2028text", prompt["message"]!!.jsonPrimitive.content)
        assertEquals("iVBORw==", prompt["images"]!!.jsonArray.single().jsonObject["data"]!!.jsonPrimitive.content)
        session.onStdout("""{"id":"${prompt["id"]!!.jsonPrimitive.content}","type":"response","command":"prompt","success":true}""")
        assertFalse(session.takeTurnEnded())
        session.onStdout("""{"type":"agent_end","willRetry":true,"messages":[]}""")
        assertFalse(session.takeTurnEnded())
        assertTrue(session.insert("new instruction"))
        runCurrent(); assertEquals("steer", sent.receive()["type"]!!.jsonPrimitive.content)
        session.onStdout("""{"type":"agent_settled"}""")
        assertTrue(session.takeTurnEnded()); assertFalse(session.insert("late"))
        assertTrue(session.onStdout("""{"type":"agent_settled"}""").isEmpty())
        session.submit(AgentTurn(RequestId("next"), "second"))
        runCurrent(); assertEquals("second", sent.receive()["message"]!!.jsonPrimitive.content)
        session.release(); runCurrent(); assertTrue(writer.isCompleted)
    }
    @Test fun `startup identity and RPC rejections cannot become successful turns`() = runTest {
        for (response in listOf(
            """{"id":"mobby-0","type":"response","command":"get_state","success":true,"data":{"sessionId":"wrong"}}""",
            """{"id":"mobby-0","type":"response","command":"get_state","success":false,"error":"missing session"}""",
            "bad-json"
        )) {
            val session = PiRpcSession("saved")
            session.submit(AgentTurn(RequestId("r"), "prompt"))
            val facts = session.onStdout(response).flatMap { ProtocolDecoder(AgentId.PI).decode(it) }
            assertFalse(facts.filterIsInstance<AgentFact.Completed>().single().success)
            assertTrue(session.takeTurnEnded()); assertNull(session.sessionId())
            session.close()
        }
    }
    @Test fun `cold and resumed launch use the exact session without putting prompt in argv`() {
        val request = RunRequest(RequestId("r"), AgentId.PI, WorkspaceRef("default"), emptyList(), "vendor/model", GatewayProfileRef("g", 0))
        val cold = AgentSessions.connect(request, "/pi", "/workspace", AgentTurn(RequestId("r"), "private prompt"))
        assertTrue("--session-id" in cold.arguments)
        assertFalse(cold.arguments.contains("private prompt"))
        cold.session.close()
        val warm = AgentSessions.connect(request.copy(sessionRef = SessionRef("saved")), "/pi", "/workspace", AgentTurn(RequestId("r"), "private prompt"))
        assertEquals("saved", warm.arguments[warm.arguments.indexOf("--session") + 1])
        assertFalse("--session-id" in warm.arguments); warm.session.close()
    }
    @Test fun `decoder keeps streamed text reasoning and tool outcomes without replaying final snapshots`() {
        val decoder = ProtocolDecoder(AgentId.PI)
        decoder.decode("""{"type":"message_start","message":{"role":"assistant"}}""")
        assertEquals("你\u2028好", decoder.decode("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"你\u2028好"}}""").filterIsInstance<AgentFact.Text>().single().text)
        assertEquals("思考", decoder.decode("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":1,"delta":"思考"}}""").filterIsInstance<AgentFact.Tool>().single().output)
        val tool = decoder.decode("""{"type":"tool_execution_start","toolCallId":"read-1","toolName":"read","args":{"path":"fixture.txt"}}""").filterIsInstance<AgentFact.Tool>().single()
        assertEquals(StepBody.FileRead("fixture.txt"), tool.body)
        val result = decoder.decode("""{"type":"tool_execution_end","toolCallId":"read-1","toolName":"read","result":{"content":[{"type":"text","text":"failed"}]},"isError":true}""").filterIsInstance<AgentFact.Tool>().single()
        assertNull(result.body); assertEquals(ToolOutcome.FAILED, result.outcome)
        val final = decoder.decode("""{"type":"message_end","message":{"role":"assistant","stopReason":"stop","content":[{"type":"text","text":"你\u2028好"},{"type":"thinking","thinking":"思考"}]}}""")
        assertTrue(final.none { it is AgentFact.Text }); assertTrue(final.none { it is AgentFact.Completed })
        assertTrue(decoder.decode("""{"type":"agent_end","messages":[],"willRetry":false}""").isEmpty())
        assertTrue(decoder.decode("""{"type":"agent_settled"}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
    @Test fun `errors aborts and output limits remain failures and a recovered retry can succeed`() {
        for (reason in listOf("error", "aborted", "length", "toolUse", "pending")) {
            val decoder = ProtocolDecoder(AgentId.PI)
            decoder.decode("""{"type":"message_end","message":{"role":"assistant","stopReason":"$reason","content":[]}}""")
            assertFalse(decoder.decode("""{"type":"agent_settled"}""").filterIsInstance<AgentFact.Completed>().single().success)
        }
        val decoder = ProtocolDecoder(AgentId.PI)
        decoder.decode("""{"type":"message_end","message":{"role":"assistant","stopReason":"error","content":[]}}""")
        decoder.decode("""{"type":"agent_end","willRetry":true,"messages":[]}""")
        decoder.decode("""{"type":"message_end","message":{"role":"assistant","stopReason":"stop","content":[{"type":"text","text":"recovered"}]}}""")
        assertTrue(decoder.decode("""{"type":"agent_settled"}""").filterIsInstance<AgentFact.Completed>().single().success)
    }
}
