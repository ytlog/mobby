package com.github.ytlog.mobby.android.localmodel

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

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
            """{"model":"qwen","messages":[{"role":"user","content":"hi"}],"tool_choice":"required"}""" to "chat",
        ).forEach { (body, protocol) ->
            assertThrows(BadRequest::class.java) { Protocol.parse(body, protocol) }
        }
    }

    @Test fun parsesResponsesFunctionToolAndResultWithoutLosingCallId() {
        val request = Protocol.parse("""{"model":"qwen","input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"read file"}]},{"type":"function_call","call_id":"call_1","name":"read_file","arguments":"{\"path\":\"a.txt\"}"},{"type":"function_call_output","call_id":"call_1","output":"hello"}],"tools":[{"type":"function","name":"read_file","description":"Read a file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}],"tool_choice":"auto","parallel_tool_calls":false,"stream":true,"store":false}""", "responses")
        assertEquals("call_1", request.toolContext!!.messages[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call_1", request.toolContext!!.messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("read_file", request.toolContext!!.tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun parsesMessagesToolUseAndResult() {
        val request = Protocol.parse("""{"model":"gemma","messages":[{"role":"user","content":"run"},{"role":"assistant","content":[{"type":"tool_use","id":"tool_1","name":"command","input":{"cmd":"pwd"}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool_1","content":"/tmp"}]}],"tools":[{"name":"command","input_schema":{"type":"object","properties":{"cmd":{"type":"string"}}}}],"max_tokens":128}""", "messages")
        assertEquals("tool_1", request.toolContext!!.messages[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool_1", request.toolContext!!.messages[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test fun acceptsNativeAgentMetadataAndSamplingWithoutDiscardingTools() {
        val codex = Protocol.parse("""{"model":"qwen","instructions":"assist","input":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"rules"}]},{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}],"tools":[{"type":"function","name":"exec_command","strict":false,"parameters":{"type":"object"}}],"reasoning":{"summary":"auto"},"include":["reasoning.encrypted_content"],"client_metadata":{"client":"test"},"prompt_cache_key":"synthetic","tool_choice":"auto","parallel_tool_calls":true,"store":false,"stream":true}""", "responses")
        assertEquals(3, codex.toolContext!!.messages.size)
        assertTrue(codex.toolContext!!.parallel)

        val claude = Protocol.parse("""{"model":"gemma","system":[{"type":"text","text":"assist","cache_control":{"type":"ephemeral"}}],"messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}],"tools":[{"name":"Bash","input_schema":{"type":"object"}}],"metadata":{"user_id":"synthetic"},"temperature":1,"max_tokens":1024,"stream":true}""", "messages")
        assertEquals("assist", claude.toolContext!!.messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(1f, claude.toolContext!!.temperature)
        assertEquals(1024, claude.maxTokens)
    }

    @Test fun preservesParallelCallsAndRejectsOrphanResults() {
        val request = Protocol.parse("""{"model":"qwen","input":[{"role":"user","content":"check"},{"type":"function_call","call_id":"a","name":"one","arguments":"{}"},{"type":"function_call","call_id":"b","name":"two","arguments":"{}"},{"type":"function_call_output","call_id":"a","output":"1"},{"type":"function_call_output","call_id":"b","output":"2"}],"tools":[{"type":"function","name":"one","parameters":{"type":"object"}},{"type":"function","name":"two","parameters":{"type":"object"}}]}""", "responses")
        assertEquals(2, request.toolContext!!.messages[1].jsonObject["tool_calls"]!!.jsonArray.size)
        assertThrows(BadRequest::class.java) {
            Protocol.parse("""{"model":"qwen","input":[{"type":"function_call_output","call_id":"missing","output":"x"}],"tools":[{"type":"function","name":"one","parameters":{"type":"object"}}]}""", "responses")
        }
    }
}
