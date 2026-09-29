package com.github.ytlog.mobby.android.runtime.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

class ClaudePackageInstallerTest {
    private fun archive(extra: String? = null): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
            val files = linkedMapOf("cli.js" to "official cli", "package.json" to "{\"version\":\"1.0\"}",
                "LICENSE.md" to "original upstream license", "README.md" to "readme", "sdk-tools.d.ts" to "types")
            if (extra != null) files[extra] = "unsafe"
            for ((name, text) in files) {
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry("package/$name").apply { size = bytes.size.toLong() })
                tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }
    private fun fixture(data: ByteArray, block: (File, File) -> Unit) {
        val prefix = Files.createTempDirectory("claude-installer-test").toFile()
        try {
            val integrity = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data))
            File(prefix, "share/mobby").mkdirs()
            File(prefix, "share/mobby/agents.lock.json").writeText("""{"npm":[{"name":"@anthropic-ai/claude-code","version":"1.0","delivery":"device-download","url":"https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-1.0.tgz","integrity":"sha512-$integrity"}]}""")
            val target = File(prefix, "lib/node_modules/@anthropic-ai/claude-code")
            target.mkdirs(); File(target, "cli.js").writeText("previous cli")
            block(prefix, target)
        } finally { prefix.deleteRecursively() }
    }
    @Test fun verifiedPackagePreservesLicenseAndSkipsRepeatedDownload() {
        val data = archive()
        fixture(data) { prefix, target ->
        var downloads = 0
        val installer = ClaudePackageInstaller(prefix) { downloads++; ByteArrayInputStream(data) }
        runBlocking { installer.install(); installer.install() }
        assertEquals(1, downloads)
        assertEquals("original upstream license", File(target, "LICENSE.md").readText())
        assertEquals("official cli", File(target, "cli.js").readText())
        }
    }
    @Test fun integrityFailurePreservesPreviousProgram() = fixture(archive()) { prefix, target ->
        try { runBlocking { ClaudePackageInstaller(prefix) { ByteArrayInputStream("tampered".toByteArray()) }.install() }; fail() }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("integrity")) }
        assertEquals("previous cli", File(target, "cli.js").readText())
        assertFalse(File(target, ".mobby-integrity").exists())
    }
    @Test fun traversalIsRejectedBeforePublishingInstallation() {
        val data = archive("../../escape")
        fixture(data) { prefix, target ->
            try { runBlocking { ClaudePackageInstaller(prefix) { ByteArrayInputStream(data) }.install() }; fail() }
            catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Unsafe")) }
            assertEquals("previous cli", File(target, "cli.js").readText())
            assertFalse(File(prefix, "lib/escape").exists())
        }
    }
    @Test fun cancellationIsNotReportedAsInstallationSuccess() = fixture(archive()) { prefix, target ->
        try { runBlocking { ClaudePackageInstaller(prefix) { throw CancellationException("cancelled") }.install() }; fail() }
        catch (_: CancellationException) { }
        assertEquals("previous cli", File(target, "cli.js").readText())
        assertFalse(target.parentFile!!.listFiles()!!.any { it.name.startsWith(".claude-install-") })
    }
}
