package com.github.ytlog.mobby.android.localmodel

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun parsesTextOnlyRequests() {
        val responses = Protocol.parse("""{"model":"qwen","input":[{"role":"user","content":[{"type":"input_text","text":"hello"}]}],"max_output_tokens":16}""", "responses")
        assertEquals("hello", responses.messages.single().content)
        assertEquals(16, responses.maxTokens)
        val messages = Protocol.parse("""{"model":"gemma","messages":[{"role":"user","content":"hi"}],"max_tokens":8}""", "messages")
        assertEquals("hi", messages.messages.single().content)
    }

    @Test fun rejectsUnsupportedAgentSemanticsBeforeGeneration() {
        listOf(
            """{"model":"qwen","input":"hi","tools":[{"type":"function"}]}""" to "responses",
            """{"model":"qwen","messages":[{"role":"user","content":[{"type":"image","source":{}}]}]}""" to "messages",
            """{"model":"qwen","messages":[{"role":"user","content":"hi"}],"tool_choice":"auto"}""" to "chat",
        ).forEach { (body, protocol) ->
            assertThrows(BadRequest::class.java) { Protocol.parse(body, protocol) }
        }
    }
}
