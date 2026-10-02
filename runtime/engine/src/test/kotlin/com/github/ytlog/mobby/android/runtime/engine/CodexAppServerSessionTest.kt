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
    @Test fun `a new conversation starts an independent thread on the live server`() = runTest {
        val session = CodexAppServerSession("/workspace", "model", null).also { it.submit(turn) }
        val sent = Channel<JsonObject>(8)
        backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
        runCurrent(); sent.receive() // initialize
        session.onStdout("""{"id":"1","result":{}}"""); runCurrent()
        sent.receive() // initialized
        assertEquals("thread/start", sent.receive().getValue("method").jsonPrimitive.content)
        session.onStdout("""{"id":"2","result":{"thread":{"id":"thread-one"}}}"""); runCurrent()
        assertEquals("turn/start", sent.receive().getValue("method").jsonPrimitive.content)
        session.onStdout("""{"id":"3","result":{"turn":{"id":"turn-one"}}}""")
        session.onStdout("""{"method":"turn/completed","params":{"turn":{"id":"turn-one","status":"completed"}}}""")
        assertTrue(session.takeTurnEnded())
        assertTrue(session.startNewThread(AgentTurn(RequestId("next"), "fresh")))
        runCurrent()
        val freshThread = sent.receive()
        assertEquals("thread/start", freshThread.getValue("method").jsonPrimitive.content)
        assertFalse(freshThread.getValue("params").jsonObject.containsKey("threadId"))
        assertNull(session.sessionId())
        session.onStdout("""{"id":"4","result":{"thread":{"id":"thread-two"}}}"""); runCurrent()
        val freshTurn = sent.receive()
        assertEquals("thread-two", freshTurn.getValue("params").jsonObject.getValue("threadId").jsonPrimitive.content)
        assertEquals("fresh", freshTurn.getValue("params").jsonObject.getValue("input").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(session.onStdout("""{"method":"item/agentMessage/delta","params":{"threadId":"thread-one","itemId":"old","delta":"stale"}}""").isEmpty())
        session.close()
    }
    @Test fun `cold start resumes only when asked and the next turn is just turn start`() = runTest {
        for (saved in listOf(null, "session-123")) {
            val threadId = saved ?: "thread-1"
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
            val started = session.onStdout("""{"id":"2","result":{"thread":{"id":"$threadId"}}}""")
            assertEquals(listOf(AgentFact.Session(threadId)), started.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            runCurrent()
            val first = sent.receive()
            assertEquals("turn/start", first.getValue("method").jsonPrimitive.content)
            assertFalse(session.insert("too early"))
            session.onStdout("""{"id":"3","result":{"turn":{"id":"turn-1"}}}""")
            assertTrue(session.insert("more context")); runCurrent()
            val steer = sent.receive()
            assertEquals("turn/steer", steer.getValue("method").jsonPrimitive.content)
            assertEquals("turn-1", steer.getValue("params").jsonObject.getValue("expectedTurnId").jsonPrimitive.content)
            assertEquals("more context", steer.getValue("params").jsonObject.getValue("input").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertTrue(session.onStdout("""{"id":"4","result":{}}""").isEmpty())
            val input = first.getValue("params").jsonObject.getValue("input").jsonArray
            assertEquals("look", input[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("/private/image.png", input[1].jsonObject.getValue("path").jsonPrimitive.content)
            assertEquals("object", first.getValue("params").jsonObject.getValue("outputSchema").jsonObject.getValue("type").jsonPrimitive.content)
            val thought = session.onStdout("""{"method":"item/reasoning/textDelta","params":{"delta":"先想","itemId":"r","threadId":"$threadId","turnId":"u"}}""")
            assertEquals(listOf(AgentFact.Tool("r", StepBody.Thinking, "先想")), thought.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            val answer = session.onStdout("""{"method":"item/completed","params":{"item":{"id":"a","type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"<arg_value>已写好</arg_value>"}]}}}""")
            assertEquals(listOf(AgentFact.Text("a", "已写好")), answer.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            val delta = session.onStdout("""{"method":"item/agentMessage/delta","params":{"delta":"你","itemId":"m","threadId":"$threadId","turnId":"u"}}""")
            assertEquals(listOf(AgentFact.Text("m", "你")), delta.flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) })
            assertTrue(session.onStdout("""{"method":"turn/completed","params":{"threadId":"$threadId","turn":{"id":"u","items":[],"status":"completed"}}}""").flatMap { ProtocolDecoder(AgentId.CODEX).decode(it) }.filterIsInstance<AgentFact.Completed>().single().success)
            assertTrue(session.takeTurnEnded())
            assertFalse(session.insert("late"))
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
