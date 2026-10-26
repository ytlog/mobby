package com.github.ytlog.mobby.android.runtime.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NativeLinksTest {
    private val root = Files.createTempDirectory("links").toFile()
    private val prefix = File(root, "usr").apply { mkdirs() }
    private val mapping = mapOf("bin/node" to "libnode.so")
    @After fun clean() { root.deleteRecursively() }

    private fun nativeDir(name: String) = File(root, name).apply { mkdirs(); File(this, "libnode.so").writeText(name) }
    private fun node() = File(prefix, "bin/node")

    /** An app update moves the native library directory and leaves the old links dangling. */
    @Test fun `repair points links at the current native directory when the runtime files are current`() {
        val old = nativeDir("old")
        NativeLinks.link(prefix, old.absolutePath, mapping)
        old.deleteRecursively()
        assertFalse(node().exists())
        File(root, ".mobby-bootstrap").writeText("v2")
        assertTrue(NativeLinks.repair(root, prefix, nativeDir("new").absolutePath, "v2", mapping))
        assertEquals("new", node().readText())
    }

    @Test fun `repair refuses when the bundled runtime changed and needs a full preparation`() {
        File(root, ".mobby-bootstrap").writeText("v1")
        assertFalse(NativeLinks.repair(root, prefix, nativeDir("new").absolutePath, "v2", mapping))
        assertFalse(node().exists())
        File(root, ".mobby-bootstrap").delete()
        assertFalse(NativeLinks.repair(root, prefix, nativeDir("new").absolutePath, "v2", mapping))
    }
}
