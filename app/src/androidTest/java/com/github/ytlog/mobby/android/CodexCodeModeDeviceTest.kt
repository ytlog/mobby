package com.github.ytlog.mobby.android

import androidx.test.platform.app.InstrumentationRegistry
import com.github.ytlog.mobby.android.runtime.android.RuntimeEnvironment
import com.github.ytlog.mobby.android.runtime.android.gateway.GatewayConfig
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.*
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Packaged Codex app-server with a code-mode-only model, started by the app process itself (untrusted_app),
 * through the installed gateway. Isolated HOME, fake key, host mock via adb reverse; see codex-code-mode-smoke.cjs.
 */
class CodexCodeModeDeviceTest {
    @Test fun codeModeOnlyModelRunsShellThroughPackagedHost() = runBlocking {
        val endpoint = InstrumentationRegistry.getArguments().getString("codexMockEndpoint")
        assumeTrue("Run with codex-code-mode-smoke.cjs", endpoint != null)
        val model = InstrumentationRegistry.getArguments().getString("codexModel") ?: "gpt-5.6-sol"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeEnvironment(context)
        withTimeout(150_000) { runtime.initialize {} }
        assertTrue(runtime.dependenciesReady)
        val root = File(context.filesDir, "codex-code-mode-test-${UUID.randomUUID()}").apply { mkdirs() }
        val home = File(root, "home").apply { mkdirs() }
        val codexHome = File(home, ".codex").apply { mkdirs() }
        val work = File(root, "work").apply { mkdirs() }
        val tmp = File(root, "tmp").apply { mkdirs() }
        val config = GatewayConfig(requireNotNull(endpoint), model, "fake-codex-device-key")
        val request = RunRequest(RequestId("codex-code-mode-device-test"), AgentId.CODEX, WorkspaceRef("default"), emptyList(),
            model, GatewayProfileRef("isolated-test", 0))
        val connection = AgentSessions.connect(request, runtime.executable(AgentMode.CODEX), work.absolutePath,
            AgentTurn(RequestId("first"), "ANDROID_CODE_MODE_TURN"))
        val session = connection.session
        val decoder = ProtocolDecoder(AgentId.CODEX)
        val facts = mutableListOf<AgentFact>()
        val diagnostics = StringBuilder()
        var exit: Int? = null
        try {
            withTimeout(120_000) {
                runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
                    File(context.filesDir, "gateway.cjs").absolutePath, "CODEX") + connection.arguments, work,
                    mapOf("HOME" to home.absolutePath, "CODEX_HOME" to codexHome.absolutePath, "TMPDIR" to tmp.absolutePath,
                        "MOBBY_GATEWAY_CONFIG" to config.json(), "MOBBY_LANGUAGE" to "en"),
                    input = session.input, onTerminated = { exit = it }).collect { line ->
                    when (line) {
                        is OutputLine.Stdout -> {
                            session.onStdout(line.text).forEach { facts += decoder.decode(it) }
                            if (session.takeTurnEnded()) session.release()
                        }
                        is OutputLine.Stderr -> diagnostics.appendLine(line.text)
                        is OutputLine.Exit -> exit = line.code
                    }
                }
            }
            val evidence = diagnostics.toString().take(2000) + "\n" + facts.joinToString("\n").take(3000)
            assertFalse(evidence, diagnostics.contains("code-mode host"))
            assertFalse(evidence, facts.joinToString().contains("Code Mode is unavailable"))
            assertEquals(evidence, 0, exit)
            assertTrue(evidence, facts.filterIsInstance<AgentFact.Completed>().any { it.success })
            // Written by the nested shell tool that the model's code-mode JavaScript called.
            assertEquals(evidence, "ANDROID_CODE_MODE_SHELL_OK", File(work, "result.txt").readText())
            delay(1_000)
            val leftovers = File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }.filter { process ->
                runCatching { File(process, "cmdline").readText() }.getOrDefault("").contains("codex-code-mode-host")
            }
            assertTrue("Code-mode host must stop with Codex: ${leftovers.map { it.name }}", leftovers.isEmpty())
        } finally {
            session.close()
            root.deleteRecursively()
        }
    }
}
