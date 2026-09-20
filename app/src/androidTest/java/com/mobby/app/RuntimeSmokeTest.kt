package com.mobby.app

import androidx.test.platform.app.InstrumentationRegistry
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Runs against the actual packaged ARM64 SDK, without network calls or model credentials. */
class RuntimeSmokeTest {
    private lateinit var runtime: RuntimeEnvironment
    @Before fun initialize() = runBlocking {
        runtime = RuntimeEnvironment(InstrumentationRegistry.getInstrumentation().targetContext)
        withTimeout(120_000) { runtime.initialize {} }
    }
    @Test fun automaticInstallationProvidesExecutableDependencies() = runBlocking {
        assertTrue("All five dependency version checks must succeed", runtime.dependenciesReady)
        assertTrue(File(runtime.workspace, ".git").isDirectory)
        val result = runtime.run(AgentMode.SHELL, "node -e 'console.log(6*7)' && git rev-parse --is-inside-work-tree").toList()
        assertTrue(result.contains(OutputLine.Stdout("42")))
        assertTrue(result.contains(OutputLine.Stdout("true")))
        assertEquals(OutputLine.Exit(0), result.last())
    }
    @Test fun streamsBeforeExitAndCancelsChildren() = runBlocking {
        val marker = File(runtime.workspace, "cancel-smoke-marker")
        marker.delete()
        try {
            val first = withTimeout(10_000) {
                runtime.run(AgentMode.SHELL, "(sleep 2; printf leaked > cancel-smoke-marker) & printf 'started\\n'; wait").first()
            }
            assertEquals(OutputLine.Stdout("started"), first)
            delay(2500)
            assertFalse("Cancelled descendants must not write a marker", marker.exists())
        } finally { marker.delete() }
    }
    @Test fun drainsBothPipesAndReportsExit() = runBlocking {
        val events = withTimeout(30_000) {
            runtime.run(AgentMode.SHELL, "i=0; while [ \$i -lt 6000 ]; do printf 'output line %s\\n' \"\$i\"; printf 'error line %s\\n' \"\$i\" >&2; i=\$((i+1)); done; exit 7").toList()
        }
        assertEquals(6000, events.count { it is OutputLine.Stdout })
        assertEquals(6000, events.count { it is OutputLine.Stderr })
        assertEquals(OutputLine.Exit(7), events.last())
        assertEquals(1, events.count { it is OutputLine.Exit })
    }
    @Test fun timeoutCleansUpAndNextCommandRuns() = runBlocking {
        try {
            withTimeout(300) { runtime.run(AgentMode.SHELL, "sleep 120 & wait").collect() }
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) { }
        val events = withTimeout(5_000) { runtime.run(AgentMode.SHELL, "printf 'ready\\n'").toList() }
        assertEquals(OutputLine.Stdout("ready"), events.first())
        assertEquals(OutputLine.Exit(0), events.last())
    }
}
