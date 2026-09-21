package com.mobby.runtime.engine

import com.mobby.runtime.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Opt-in real pinned CLI test. Isolated HOME and fake model only; no saved gateway/config access. */
class ClaudeControlHostTest {
    @Test fun `production control session allows denies and cancels real Claude writes`() = runBlocking {
        val cli = System.getenv("MOBBY_TEST_CLAUDE_JS")
        assumeTrue("Set MOBBY_TEST_CLAUDE_JS for real CLI gate", cli != null)
        val fixture = File("../runtime/gateway-tests/approval-control-fixture.cjs").canonicalFile
        var resumeHome: File? = null
        var resumeId: String? = null
        val roots = mutableListOf<File>()
        try { for (mode in listOf("allow", "resume", "deny", "cancel")) {
            val root = if (mode == "resume") resumeHome!! else Files.createTempDirectory("mobby-kotlin-control-").toFile().also { roots += it }
            if (mode == "resume") { assertNotNull(resumeId); assertTrue(File(root, "approved.txt").delete()) }
            val process = ProcessBuilder(listOf("node", fixture.path, cli!!, root.path) + if (mode == "resume") listOf(resumeId!!) else emptyList()).start()
            val control = ClaudeControlSession(RequestId(mode), buildJsonObject {
                put("type", "user"); putJsonObject("message") { put("role", "user"); put("content", "Write approved.txt using the supplied content, requesting permission as needed.") }
            })
            var permissions = 0
            var terminal: AgentFact.Completed? = null
            val target = File(root, "approved.txt")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writer = scope.async { try { process.outputStream.use { stream -> control.input.collect { stream.write(it); stream.flush() } } } catch (e: Exception) { if (mode != "cancel") throw e } }
            val diagnostics = scope.launch { process.errorStream.bufferedReader().use { while (it.readLine() != null) { /* Never persist CLI diagnostics or user configuration. */ } } }
            try {
                withTimeout(55_000) {
                    withContext(Dispatchers.IO) {
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (!control.onStdout(line)) continue
                                val facts = ProtocolDecoder(AgentId.CLAUDE_CODE).decode(line)
                                if (mode == "allow") facts.filterIsInstance<AgentFact.Session>().firstOrNull()?.let { resumeId = it.id; resumeHome = root }
                                if (facts.any { it is AgentFact.Approval }) {
                                    permissions++
                                    assertFalse("Write happened before explicit decision", target.exists())
                                    val approval = facts.filterIsInstance<AgentFact.Approval>().single()
                                    if (mode == "cancel") { control.close(); process.destroy(); break }
                                    assertTrue(control.offer(RequestId(mode), approval.id, if (mode in listOf("allow", "resume")) ApprovalChoice.ALLOW_ONCE else ApprovalChoice.DENY))
                                }
                                facts.filterIsInstance<AgentFact.Completed>().singleOrNull()?.let { terminal = it }
                            }
                        }
                        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                    }
                }
                assertEquals(1, permissions)
                if (mode in listOf("allow", "resume")) { assertEquals(0, process.exitValue()); assertEquals(true, terminal?.success); assertEquals("KOTLIN_CONTROL_OK\n", target.readText()) }
                else { assertFalse(target.exists()); if (mode == "deny") assertEquals(ErrorCode.PERMISSION_DENIED, terminal?.error) else assertNotEquals(0, process.exitValue()) }
                if (mode != "cancel") writer.await()
            } finally {
                control.close(); process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                writer.cancel(); diagnostics.cancel(); scope.cancel()
            }
        } } finally { roots.forEach { it.deleteRecursively() } }
        Unit
    }
}
