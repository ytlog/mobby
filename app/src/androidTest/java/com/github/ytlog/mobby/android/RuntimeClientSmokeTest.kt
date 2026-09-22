package com.github.ytlog.mobby.android

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The installed application's real service/client boundary; never logs gateway fields or credentials. */
class RuntimeClientSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val host get() = (instrumentation.targetContext.applicationContext as MobbyApplication).runtime
    @Before fun ready() = runBlocking {
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ready = withTimeout(150_000) { host.admin.environment.first { it.phase != EnvironmentPhase.INITIALIZING } }
        assertEquals(EnvironmentPhase.READY, ready.phase)
    }
    @Test fun shellSharesGlobalSlotAndStopReportsConfirmedTermination() = runBlocking {
        assertEquals(CommandResult.Accepted, host.diagnostics.executeShell("printf 'STARTED\\n'; sleep 120"))
        withTimeout(10_000) { host.diagnostics.state.first { it.output.contains("STARTED") } }
        assertEquals(ErrorCode.BUSY, (host.diagnostics.executeShell("printf 'SHOULD_NOT_RUN'") as CommandResult.Rejected).error.code)
        assertEquals(CommandResult.Accepted, host.diagnostics.stopShell())
        val stopped = withTimeout(10_000) { host.diagnostics.state.first { it.phase?.terminal == true } }
        assertEquals(RunPhase.CANCELLED, stopped.phase)
        assertEquals(CommandResult.Accepted, host.diagnostics.executeShell("node -e 'console.log(6*7)'"))
        val next = withTimeout(10_000) { host.diagnostics.state.first { it.phase?.terminal == true } }
        assertEquals(RunPhase.SUCCEEDED, next.phase)
        assertTrue(next.output.contains("42"))
    }
    @Test fun configuredAgentsCompleteAndResumeThroughPersistentRuntime() = runBlocking {
        assumeTrue("Real gateway test requires explicit instrumentation opt-in", InstrumentationRegistry.getArguments().getString("realGateway") == "true")
        val profiles = (host.admin.listGatewayProfiles() as AdminResult.Success).value
        assertEquals(2, profiles.size)
        for (profile in profiles) {
            assertTrue("Gateway must already be configured on device", profile.model.isNotBlank())
            val request = RunRequest(RequestId(UUID.randomUUID().toString()), profile.agent, WorkspaceRef("default"),
                listOf(InputPart.Text("Remember this identifier for this conversation: MOBBY_DEVICE_OK. Reply with that identifier only. Do not use tools.")), profile.model, profile.ref)
            val accepted = host.client.submit(request)
            assertTrue("Real submission must be accepted", accepted is SubmitResult.Accepted)
            val id = (accepted as SubmitResult.Accepted).runId
            val first = terminal(id)
            assertEquals("Protocol and exit evidence must both succeed", RunPhase.SUCCEEDED, first.phase)
            assertEquals(0, first.terminalEvidence?.exitCode)
            assertNotNull(first.sessionRef)
            assertEquals(RequestLookup.Found(id), host.client.findByRequest(request.requestId))
            assertEquals(accepted, host.client.submit(request))
            val resumed = host.client.submit(request.copy(requestId = RequestId(UUID.randomUUID().toString()),
                inputParts = listOf(InputPart.Text("What identifier did I ask you to remember? Reply with the identifier only. Do not use tools.")), sessionRef = first.sessionRef))
            assertTrue(resumed is SubmitResult.Accepted)
            val second = terminal((resumed as SubmitResult.Accepted).runId)
            assertEquals(RunPhase.SUCCEEDED, second.phase)
            val text = second.outputSegments.filterNot { it.messageId.startsWith("diagnostic:") }.map {
                val content = host.client.readArtifact(ArtifactReadRequest(it.ref, 0, 65536)) as ArtifactReadResult.Chunk
                content.bytes.toByteArray().toString(Charsets.UTF_8)
            }.joinToString("\n")
            assertTrue("Resumed CLI session must retain previous context", text.contains("MOBBY_DEVICE_OK"))
        }
    }
    private suspend fun terminal(id: RunId): RunSnapshot = withTimeout(600_000) {
        host.client.observe(id).mapNotNull { update ->
            if (update is RuntimeUpdate.Baseline) update.snapshot else (host.client.snapshot(id) as? SnapshotResult.Found)?.snapshot
        }.first { it.phase.terminal }
    }
}
