package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.*
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
    @Test fun `new imports evict least recently used attachments and reads refresh recency`() {
        val root = temporary.newFolder()
        var limit = Long.MAX_VALUE
        val store = ResourceStore(root) { limit }
        val a = ImportResourceRequest(workspace, "a", "one".toByteArray())
        val b = ImportResourceRequest(workspace, "b", "two".toByteArray())
        val c = ImportResourceRequest(workspace, "c", "new".toByteArray())
        val first = store.save(a)
        val size = root.listFiles()!!.single().length()
        limit = size * 2
        val second = store.save(b)
        assertEquals("one", store.read(first.ref, workspace).second)
        val third = store.save(c)
        assertThrows(java.io.FileNotFoundException::class.java) { store.read(second.ref, workspace) }
        assertEquals("one", store.read(first.ref, workspace).second)
        assertEquals("new", store.read(third.ref, workspace).second)
        assertEquals(limit, root.listFiles()!!.sumOf { it.length() })
        assertEquals(first, store.save(a))
    }
    @Test fun `an item too large for the cache cannot evict existing attachments`() {
        val root = temporary.newFolder()
        var limit = Long.MAX_VALUE
        val store = ResourceStore(root) { limit }
        val request = ImportResourceRequest(workspace, "a", "中文".toByteArray())
        val saved = store.save(request)
        val persisted = root.listFiles()!!.single().readBytes()
        limit = 0
        assertEquals(saved, ResourceStore(root) { limit }.save(request))
        assertThrows(ResourceStore.QuotaExceeded::class.java) { store.save(ImportResourceRequest(workspace, "b", request.bytes)) }
        assertArrayEquals(persisted, root.listFiles()!!.single().readBytes())
    }
    @Test fun `a rejected foreign workspace read does not protect an entry from eviction`() {
        val root = temporary.newFolder()
        var limit = Long.MAX_VALUE
        val store = ResourceStore(root) { limit }
        val first = store.save(ImportResourceRequest(workspace, "a", "one".toByteArray()))
        val second = store.save(ImportResourceRequest(workspace, "b", "two".toByteArray()))
        limit = root.listFiles()!!.sumOf { it.length() }
        assertThrows(IllegalArgumentException::class.java) {
            store.read(first.ref, WorkspaceRef("local-12345678-1234-1234-1234-123456789abc"))
        }
        store.save(ImportResourceRequest(workspace, "c", "new".toByteArray()))
        assertThrows(java.io.FileNotFoundException::class.java) { store.read(first.ref, workspace) }
        assertEquals("two", store.read(second.ref, workspace).second)
    }
    @Test fun `two store instances serialize eviction and stay within the shared budget`() {
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
            assertEquals(2, results.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) })
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
