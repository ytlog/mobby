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
    @Test fun `attachments belong to their selected workspace and survive reopening`() {
        val root = temporary.newFolder()
        val other = WorkspaceRef("local-12345678-1234-1234-1234-123456789abc")
        val store = ResourceStore(root)
        val original = store.save(ImportResourceRequest(workspace, "file", "same".toByteArray()))
        val selected = store.save(ImportResourceRequest(other, "file", "same".toByteArray()))
        assertEquals("""{"name":"file","text":"same"}""", File(root, original.ref.value.substringAfter(':')).readText())
        assertNotEquals(original.ref, selected.ref)
        assertEquals("same", ResourceStore(root).read(selected.ref, other).second)
        assertEquals("same", ResourceStore(root).read(original.ref, workspace).second)
        assertThrows(Exception::class.java) { store.read(selected.ref, workspace) }
        assertThrows(Exception::class.java) { store.read(original.ref, other) }
    }
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
    @Test fun `storage quota rejects new imports without deleting existing attachments and allows reuse after reduction`() {
        val root = temporary.newFolder()
        var limit = Long.MAX_VALUE
        val store = ResourceStore(root) { limit }
        val request = ImportResourceRequest(workspace, "a", "中文".toByteArray())
        val saved = store.save(request)
        val persisted = root.listFiles()!!.single().readBytes()
        limit = persisted.size.toLong()
        assertEquals(saved, store.save(request))
        assertThrows(ResourceStore.QuotaExceeded::class.java) { store.save(ImportResourceRequest(workspace, "b", request.bytes)) }
        assertEquals(1, root.listFiles()!!.size)
        assertArrayEquals(persisted, root.listFiles()!!.single().readBytes())
        limit = 0
        val reopened = ResourceStore(root) { limit }
        assertEquals(saved, reopened.save(request))
        assertEquals("中文", reopened.read(saved.ref, workspace).second)
        assertThrows(ResourceStore.QuotaExceeded::class.java) { reopened.save(ImportResourceRequest(workspace, "c", request.bytes)) }
        limit = persisted.size * 2L
        reopened.save(ImportResourceRequest(workspace, "b", request.bytes))
        assertEquals(limit, root.listFiles()!!.sumOf { it.length() })
    }
    @Test fun `two store instances cannot simultaneously admit imports beyond the shared budget`() {
        val request = ImportResourceRequest(workspace, "a", "fixture".toByteArray())
        val seed = temporary.newFolder()
        ResourceStore(seed).save(request)
        val limit = seed.listFiles()!!.single().length()
        val root = temporary.newFolder()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = listOf("a", "b").map { name -> workers.submit<Boolean> {
                start.await()
                try { ResourceStore(root) { limit }.save(ImportResourceRequest(workspace, name, request.bytes)); true }
                catch (_: ResourceStore.QuotaExceeded) { false }
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(1, root.listFiles()!!.size)
            assertEquals(limit, root.listFiles()!!.sumOf { it.length() })
        } finally { workers.shutdownNow() }
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
