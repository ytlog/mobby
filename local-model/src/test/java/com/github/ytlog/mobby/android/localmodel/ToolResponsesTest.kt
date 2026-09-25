package com.github.ytlog.mobby.android.localmodel

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolResponsesTest {
    @Test fun functionCallRoundTripsThroughResponsesAndSse() {
        val context = Protocol.parse("""{"model":"qwen","input":"read file","tools":[{"type":"function","name":"read_file","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}],"tool_choice":"auto","stream":true}""", "responses").toolContext!!
        val raw = """{"content":"","input_tokens":42,"output_tokens":12,"length":false,"tool_calls":[{"id":"call_1","name":"read_file","arguments":"{\"path\":\"a.txt\"}"}]}"""
        val turn = ToolResponses.parse(raw, context)
        val response = ToolResponses.completion("responses", "resp_1", "qwen", 1L, turn)
        val item = response["output"]!!.jsonArray.single().jsonObject
        assertEquals("function_call", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("{\"path\":\"a.txt\"}", item["arguments"]!!.jsonPrimitive.content)
        val events = ToolResponses.events("responses", response)
        assertTrue(events.any { it.first == "response.function_call_arguments.done" })
        assertEquals("response.completed", events.last().first)
    }

    @Test fun anthropicToolUseHasToolUseStopReasonAndStreamingBlocks() {
        val context = Protocol.parse("""{"model":"gemma","messages":[{"role":"user","content":"run"}],"tools":[{"name":"command","input_schema":{"type":"object"}}],"tool_choice":{"type":"tool","name":"command"}}""", "messages").toolContext!!
        val turn = ToolResponses.parse("""{"content":"","input_tokens":20,"output_tokens":8,"length":false,"tool_calls":[{"id":"tool_1","name":"command","arguments":"{\"cmd\":\"pwd\"}"}]}""", context)
        val response = ToolResponses.completion("messages", "msg_1", "gemma", 1L, turn)
        assertEquals("tool_use", response["stop_reason"]!!.jsonPrimitive.content)
        assertEquals("tool_1", response["content"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(ToolResponses.events("messages", response).any { it.first == "content_block_delta" && it.second["delta"]!!.jsonObject["type"]!!.jsonPrimitive.content == "input_json_delta" })
    }

    @Test fun rejectsUnknownAndMalformedModelToolCalls() {
        val context = Protocol.parse("""{"model":"qwen","input":"run","tools":[{"type":"function","name":"command","parameters":{"type":"object"}}]}""", "responses").toolContext!!
        listOf(
            """{"content":"","input_tokens":1,"output_tokens":1,"length":false,"tool_calls":[{"id":"a","name":"other","arguments":"{}"}]}""",
            """{"content":"","input_tokens":1,"output_tokens":1,"length":false,"tool_calls":[{"id":"a","name":"command","arguments":"not json"}]}""",
        ).forEach { assertThrows(IllegalStateException::class.java) { ToolResponses.parse(it, context) } }
    }

    @Test fun customToolInputIsReturnedAsCustomCall() {
        val context = Protocol.parse("""{"model":"qwen","input":"patch","tools":[{"type":"custom","name":"apply_patch","format":{"type":"text"}}]}""", "responses").toolContext!!
        val turn = ToolResponses.parse("""{"content":"","input_tokens":10,"output_tokens":10,"length":false,"tool_calls":[{"id":"call_patch","name":"apply_patch","arguments":"{\"input\":\"*** Begin Patch\\n*** End Patch\"}"}]}""", context)
        val response = ToolResponses.completion("responses", "resp_1", "qwen", 1L, turn)
        val item = response["output"]!!.jsonArray.single().jsonObject
        assertEquals("custom_tool_call", item["type"]!!.jsonPrimitive.content)
        assertEquals("*** Begin Patch\n*** End Patch", item["input"]!!.jsonPrimitive.content)
        assertTrue(ToolResponses.events("responses", response).any { it.first == "response.custom_tool_call_input.done" })
    }
}
