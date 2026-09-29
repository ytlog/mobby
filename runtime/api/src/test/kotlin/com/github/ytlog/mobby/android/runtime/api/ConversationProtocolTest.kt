package com.github.ytlog.mobby.android.runtime.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test fun `a stored step without a body is not a conversation step`() {
        val legacy = """{"stepId":"s","toolKind":"bash","summary":"ls","order":1}"""
        assertThrows(SerializationException::class.java) {
            Json { ignoreUnknownKeys = true }.decodeFromString<ToolSnapshot>(legacy)
        }
    }

    @Test fun `run events round trip as closed types`() {
        val subjects = listOf(
            ApprovalSubject.Command("ls"),
            ApprovalSubject.FileRead("/tmp/a.py"),
            ApprovalSubject.FileWrite("/tmp/a.py", "print(1)\n"),
            ApprovalSubject.FileDiff(listOf("a.py"), "--- a.py\n+++ a.py\n"),
            ApprovalSubject.Action("click", "确定"),
        )
        subjects.forEach { subject ->
            val pending = PendingApproval("approval", 2, subject)
            assertEquals(pending, json.decodeFromString<PendingApproval>(json.encodeToString(pending)))
        }
        val events = listOf(
            RuntimeEvent.RunAccepted(RunConfigSnapshot(AgentId.CODEX, WorkspaceRef("default"), "model", null, GatewayProfileRef("CODEX", 0), emptySet())),
            RuntimeEvent.RunStarted(SessionRef("session")),
            RuntimeEvent.Progress(ProgressNotice.OUTPUT_TRUNCATED),
            RuntimeEvent.CancellationRequested(CancelReason.USER_REQUEST),
            RuntimeEvent.RunFinished(RunPhase.SUCCEEDED, TerminalEvidence(true, 0)),
            RuntimeEvent.ProcessTerminationConfirmed,
            RuntimeEvent.Unknown("future", null),
        )
        events.forEach { event ->
            assertEquals(event, json.decodeFromString<RuntimeEvent>(json.encodeToString(event)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEvent.RunFinished(RunPhase.RUNNING, TerminalEvidence(null, null))
        }
    }
}
