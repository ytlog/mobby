package com.github.ytlog.mobby.android

import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.runtime.android.RuntimeEnvironment
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayConfig
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.*
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real packaged Pi and JNI process pipes; isolated HOME, fake key and a host mock via adb reverse. */
class PiRpcDeviceTest {
    @Test fun imagesToolsLiveTurnsResumeAndCancellation() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("piMockEndpoint")
        assumeTrue("Run with pi-android-smoke.cjs", endpoint != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeEnvironment(context)
        withTimeout(150_000) { runtime.initialize {} }
        assertTrue(runtime.dependenciesReady)
        val root = File(context.filesDir, "pi-rpc-test-${UUID.randomUUID()}").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        File(work, "fixture.txt").writeText("ANDROID_PI_FIXTURE_OK")
        val config = GatewayConfig(requireNotNull(endpoint), "test-model", "fake-pi-device-key")
        val request = RunRequest(RequestId("pi-device-test"), AgentId.PI, WorkspaceRef("default"), emptyList(),
            "test-model", GatewayProfileRef("isolated-test", 0))
        val facts = mutableListOf<AgentFact>()
        val image = TurnImage("image/png", "unused", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4wAAAABJRU5ErkJggg==")
        suspend fun launch(saved: SessionRef?, live: Boolean, cancel: Boolean = false): String? {
            val firstTurn = AgentTurn(RequestId("first"), if (cancel) "CANCEL_DEVICE" else "ANDROID_PI_中文\u2028$(literal)", if (saved == null) listOf(image) else emptyList())
            val connection = AgentSessions.connect(request.copy(sessionRef = saved), runtime.executable(AgentMode.PI), work.absolutePath, firstTurn)
            val session = connection.session
            var turns = 0
            var exit: Int? = null
            val diagnostics = StringBuilder()
            val decoder = ProtocolDecoder(AgentId.PI)
            try {
                withTimeout(if (cancel) 5_000 else 60_000) {
                    runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
                        File(context.filesDir, "gateway.cjs").absolutePath, "PI") + connection.arguments, work,
                        mapOf("HOME" to home.absolutePath, "TMPDIR" to tmp.absolutePath, "MOBBY_GATEWAY_CONFIG" to config.json()),
                        input = session.input, onTerminated = { exit = it }).collect { line ->
                        when (line) {
                            is OutputLine.Stdout -> {
                                session.onStdout(line.text).forEach { facts += decoder.decode(it) }
                                if (session.takeTurnEnded()) {
                                    turns++
                                    if (live && turns == 1) session.submit(AgentTurn(RequestId("second"), "SECOND_DEVICE_TURN"))
                                    else session.release()
                                }
                            }
                            is OutputLine.Stderr -> diagnostics.appendLine(line.text)
                            is OutputLine.Exit -> exit = line.code
                        }
                    }
                }
                assertFalse("A pending response must time out", cancel)
                assertEquals(diagnostics.toString().take(1000), 0, exit)
                assertEquals(if (live) 2 else 1, turns)
                return session.sessionId()
            } catch (expected: TimeoutCancellationException) {
                assertTrue(cancel)
                assertNotNull("JNI cancellation must confirm process termination", exit)
                return session.sessionId()
            } finally { session.close() }
        }
        try {
            val saved = requireNotNull(launch(null, live = true))
            assertEquals(2, facts.filterIsInstance<AgentFact.Completed>().count { it.success })
            assertTrue(facts.filterIsInstance<AgentFact.Tool>().any { it.body is StepBody.FileRead })
            assertTrue(facts.filterIsInstance<AgentFact.Tool>().any { it.outcome == ToolOutcome.SUCCEEDED })
            assertEquals("ANDROID_PI_WRITE_OK", File(work, "result.txt").readText())
            assertEquals(saved, launch(SessionRef(saved), live = false))
            assertEquals(3, facts.filterIsInstance<AgentFact.Completed>().count { it.success })
            val before = facts.filterIsInstance<AgentFact.Completed>().size
            launch(SessionRef(saved), live = false, cancel = true)
            assertEquals("Cancellation must not fabricate completion", before, facts.filterIsInstance<AgentFact.Completed>().size)
            assertTrue(tmp.listFiles().orEmpty().none { it.name.startsWith("mobby-pi-") })
        } finally { root.deleteRecursively() }
    }
}
