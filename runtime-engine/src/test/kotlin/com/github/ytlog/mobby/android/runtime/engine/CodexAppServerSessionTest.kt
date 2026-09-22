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
class CodexAppServerSessionTest {
    private val turn = AgentTurn(RequestId("turn"), "look", listOf(TurnImage("image/png", "/private/image.png", "AQI=")),
        Json.parseToJsonElement("""{"type":"object"}"""))
    @Test fun `cold start resumes only when asked and the next turn is just turn start`() = runTest {
        for (saved in listOf(null, "session-123")) {
            val session = CodexAppServerSession("/workspace", "model", saved).also { it.submit(turn) }
            val sent = Channel<JsonObject>(8)
            val writer = backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
            runCurrent()
            val initialize = sent.receive()
            assertEquals("initialize", initialize.getValue("method").jsonPrimitive.content)
            assertTrue(session.onStdout("""{"id":"1","result":{"userAgent":"codex"}}""").isEmpty()); runCurrent()
            assertEquals("initialized", sent.receive().getValue("method").jsonPrimitive.content)
            val thread = sent.receive()
            assertEquals(if (saved == null) "thread/start" else "thread/resume", thread.getValue("method").jsonPrimitive.content)
            if (saved != null) assertEquals(saved, thread.getValue("params").jsonObject.getValue("threadId").jsonPrimitive.content)
            val started = session.onStdout("""{"id":"2","result":{"thread":{"id":"${saved ?: "thread-1"}"}}}""")
            assertEquals(listOf(AgentFact.Session(saved ?: "thread-1")), started.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            runCurrent()
            val first = sent.receive()
            assertEquals("turn/start", first.getValue("method").jsonPrimitive.content)
            val input = first.getValue("params").jsonObject.getValue("input").jsonArray
            assertEquals("look", input[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("/private/image.png", input[1].jsonObject.getValue("path").jsonPrimitive.content)
            assertEquals("object", first.getValue("params").jsonObject.getValue("outputSchema").jsonObject.getValue("type").jsonPrimitive.content)
            val delta = session.onStdout("""{"method":"item/agentMessage/delta","params":{"delta":"你","itemId":"m","threadId":"t","turnId":"u"}}""")
            assertEquals(listOf(AgentFact.Text("m", "你")), delta.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            assertTrue(session.onStdout("""{"method":"turn/completed","params":{"threadId":"t","turn":{"id":"u","items":[],"status":"completed"}}}""").flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) }.filterIsInstance<AgentFact.Completed>().single().success)
            assertTrue(session.takeTurnEnded())
            assertFalse(writer.isCompleted)
            session.submit(AgentTurn(RequestId("next"), "second"))
            runCurrent()
            val second = sent.receive()
            assertEquals("turn/start", second.getValue("method").jsonPrimitive.content)
            assertEquals("second", second.getValue("params").jsonObject.getValue("input").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertFalse(second.toString().contains("thread/resume"))
            session.release(); runCurrent()
            assertTrue(writer.isCompleted)
            session.close()
        }
    }
}
