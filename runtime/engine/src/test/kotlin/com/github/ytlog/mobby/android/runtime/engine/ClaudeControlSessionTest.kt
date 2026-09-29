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
class ClaudeControlSessionTest {
    private val id = RequestId("fixture")
    private val user = Json.parseToJsonElement("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"literal"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AQI="}}]}}""").jsonObject
    private val permission = """{"type":"control_request","request_id":"p","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"/fixture/file","content":"Bearer original-token","description":"write the fixture"}}}"""
    private fun initialized(initialize: JsonObject) = """{"type":"control_response","response":{"subtype":"success","request_id":${initialize.getValue("request_id")}}}"""
    private fun session() = ClaudeControlSession().also { it.submit(AgentTurn(id, "literal", listOf(TurnImage("image/png", "/fixture.png", "AQI=")))) }
    @Test fun `handshake waits then preserves user content and a later turn appends without another initialize`() = runTest {
        for (choice in ApprovalChoice.values()) {
            val session = session()
            val sent = Channel<JsonObject>(8)
            val writer = backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
            runCurrent()
            val initialize = sent.receive()
            assertEquals("initialize", initialize.getValue("request").jsonObject.getValue("subtype").jsonPrimitive.content)
            assertTrue(sent.tryReceive().isFailure)
            assertTrue(session.onStdout(initialized(initialize)).isEmpty()); runCurrent()
            assertEquals(user, sent.receive())
            assertTrue(session.insert("more context")); runCurrent()
            assertEquals("more context", sent.receive().claudePrompt())
            assertTrue(session.onStdout(permission).isNotEmpty())
            assertTrue(session.onStdout(permission).isEmpty())
            assertFalse(session.offer(RequestId("other"), "p", choice))
            assertFalse(session.offer(id, "unknown", choice))
            assertTrue(sent.tryReceive().isFailure)
            assertTrue(session.offer(id, "p", choice))
            assertFalse(session.offer(id, "p", choice)); runCurrent()
            val response = sent.receive().getValue("response").jsonObject
            assertEquals("p", response.getValue("request_id").jsonPrimitive.content)
            val decision = response.getValue("response").jsonObject
            if (choice == ApprovalChoice.ALLOW_ONCE) assertEquals(Json.parseToJsonElement(permission).jsonObject.getValue("request").jsonObject.getValue("input"), decision.getValue("updatedInput"))
            else { assertEquals("deny", decision.getValue("behavior").jsonPrimitive.content); assertFalse(decision.containsKey("updatedInput")) }
            assertFalse(writer.isCompleted)
            assertTrue(session.onStdout("""{"type":"result","subtype":"success","is_error":false,"session_id":"session-1"}""").isNotEmpty()); runCurrent()
            assertFalse(writer.isCompleted)
            assertEquals("session-1", session.sessionId())
            assertFalse(session.insert("late"))
            session.submit(AgentTurn(RequestId("next"), "second"))
            assertEquals("second", sent.receive().claudePrompt()); runCurrent()
            session.release(); runCurrent()
            assertTrue(writer.isCompleted)
            assertFalse(session.offer(id, "p", choice))
            session.close()
        }
    }
    @Test fun `sandbox auto allow sends the original input and does not leave a card waiting`() = runTest {
        val session = session()
        val sent = Channel<JsonObject>(8)
        backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
        runCurrent()
        assertTrue(session.onStdout(initialized(sent.receive())).isEmpty()); runCurrent(); sent.receive()
        assertTrue(session.onStdout(permission, autoAllow = true).isEmpty()); runCurrent()
        val decision = sent.receive().getValue("response").jsonObject.getValue("response").jsonObject
        assertEquals("allow", decision.getValue("behavior").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(permission).jsonObject.getValue("request").jsonObject.getValue("input"), decision.getValue("updatedInput"))
        assertFalse(session.offer(id, "p", ApprovalChoice.ALLOW_ONCE))
        session.close()
    }
    @Test fun `cancel rejects later decisions and cannot replay reused permission`() = runTest {
        val session = session()
        val sent = Channel<JsonObject>(8)
        val writer = backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
        runCurrent(); session.onStdout(initialized(sent.receive())); runCurrent(); sent.receive()
        session.onStdout(permission)
        assertThrows(IllegalStateException::class.java) { session.onStdout(permission.replace("/fixture/file", "/changed")) }
        assertThrows(IllegalStateException::class.java) { session.onStdout("""{"type":"result"}""") }
        session.offer(id, "p", ApprovalChoice.ALLOW_ONCE)
        assertThrows(IllegalStateException::class.java) { session.onStdout(permission) }
        runCurrent(); sent.receive() // Already accepted input cannot be recalled by a later cancellation.
        session.close(); runCurrent()
        assertTrue(sent.tryReceive().isFailure)
        assertTrue(writer.isCompleted)
        assertFalse(session.offer(id, "p", ApprovalChoice.ALLOW_ONCE))
    }
    @Test fun `cancelling while waiting rejects both decisions without emitting a control reply`() = runTest {
        val session = session()
        val sent = Channel<JsonObject>(8)
        val writer = backgroundScope.launch { session.input.collect { sent.send(Json.parseToJsonElement(it.decodeToString()).jsonObject) } }
        runCurrent(); session.onStdout(initialized(sent.receive())); runCurrent(); sent.receive()
        session.onStdout(permission); session.close(); runCurrent()
        assertTrue(writer.isCompleted)
        assertTrue(sent.tryReceive().isFailure)
        assertFalse(session.offer(id, "p", ApprovalChoice.ALLOW_ONCE))
        assertFalse(session.offer(id, "p", ApprovalChoice.DENY))
    }
    @Test fun `failed initialization and permission before handshake cannot send user prompt`() = runTest {
        val session = session()
        assertThrows(IllegalStateException::class.java) { session.onStdout(permission) }
        assertThrows(IllegalStateException::class.java) { session.onStdout("""{"type":"control_response","response":{"subtype":"error","request_id":"wrong"}}""") }
        session.close()
    }
    private fun JsonObject.claudePrompt() = getValue("message").jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
}
