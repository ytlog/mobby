package com.github.ytlog.mobby.android.runtime.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `each conversation step round trips under its type name`() {
        val bodies = listOf(
            StepBody.Thinking,
            StepBody.Command("ls -la"),
            StepBody.FileRead("/tmp/a.py"),
            StepBody.FileWrite("/tmp/a.py"),
            StepBody.FileDiff(listOf("a.py", "b.py")),
            StepBody.Action("click", "确定"),
        )
        bodies.forEach { body ->
            val encoded = json.encodeToString(ToolSnapshot("s", body, order = 1))
            assertEquals(body, json.decodeFromString<ToolSnapshot>(encoded).body)
        }
        val reply = json.encodeToString<RuntimeEvent>(RuntimeEvent.AssistantDelta(OutputSegment("m", 0, ResourceRef("ref"))))
        assertEquals("m", (json.decodeFromString<RuntimeEvent>(reply) as RuntimeEvent.AssistantDelta).segment.messageId)
    }

    @Test fun `a stored step without a body stays readable as a generic action`() {
        val legacy = """{"stepId":"s","toolKind":"bash","summary":"ls","order":1}"""
        assertEquals(StepBody.Action("tool", ""), Json { ignoreUnknownKeys = true }.decodeFromString<ToolSnapshot>(legacy).body)
    }
}
