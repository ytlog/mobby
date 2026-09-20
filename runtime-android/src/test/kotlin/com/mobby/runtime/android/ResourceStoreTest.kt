package com.mobby.runtime.android

import com.mobby.runtime.api.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ResourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workspace = WorkspaceRef("default")
    @Test fun `imported text survives restart and prompt contains original Unicode without shell expansion`() {
        val root = temporary.newFolder(); val text = "中文\n  `touch x` ${'$'}(echo no)\n"
        val saved = ResourceStore(root).save(ImportResourceRequest(workspace, "review.md", text.toByteArray()))
        val store = ResourceStore(root)
        assertEquals(saved to text, store.read(saved.ref, workspace))
        assertEquals(saved, store.save(ImportResourceRequest(workspace, "review.md", text.toByteArray())))
        val prompt = store.prompt(listOf(InputPart.Text("review"), InputPart.Resource(saved.ref)), workspace)
        assertTrue(prompt.contains("review.md")); assertTrue(prompt.contains("中文")); assertFalse(File(root, "x").exists())
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun `binary malformed oversized and path names create no files`() {
        val root = temporary.newFolder(); val store = ResourceStore(root)
        for (bytes in listOf("%PDF-1.7\nASCII PDF content".toByteArray(), byteArrayOf(0), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(ResourceStore.MAX_BYTES + 1))) {
            assertThrows(Exception::class.java) { store.save(ImportResourceRequest(workspace, "file", bytes)) }
        }
        assertThrows(Exception::class.java) { store.save(ImportResourceRequest(workspace, "../file", "x".toByteArray())) }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun `changed missing foreign and symlink resources are rejected`() {
        val root = temporary.newFolder(); val store = ResourceStore(root)
        val saved = store.save(ImportResourceRequest(workspace, "file", "original".toByteArray()))
        assertThrows(Exception::class.java) { store.read(saved.ref, WorkspaceRef("other")) }
        assertThrows(Exception::class.java) { store.read(ResourceRef("../file"), workspace) }
        val file = root.listFiles()!!.single(); file.writeText("changed")
        assertThrows(Exception::class.java) { store.read(saved.ref, workspace) }
        file.delete(); Files.createSymbolicLink(file.toPath(), temporary.newFile().toPath())
        assertThrows(Exception::class.java) { store.read(saved.ref, workspace) }
    }
    @Test fun `total input and file count are bounded without silent truncation`() {
        val store = ResourceStore(temporary.newFolder())
        val saved = store.save(ImportResourceRequest(workspace, "file", "a".repeat(ResourceStore.MAX_BYTES).toByteArray()))
        assertThrows(Exception::class.java) { store.prompt(List(2) { InputPart.Resource(saved.ref) }, workspace) }
        assertThrows(Exception::class.java) { store.prompt(List(5) { InputPart.Resource(saved.ref) }, workspace) }
        assertTrue(store.prompt(listOf(InputPart.Resource(saved.ref)), workspace).contains("file"))
    }
}
