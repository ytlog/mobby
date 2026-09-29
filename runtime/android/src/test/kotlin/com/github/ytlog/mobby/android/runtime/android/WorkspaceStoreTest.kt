package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.api.WorkspaceRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `selected directories persist while default files remain intact`() = runBlocking {
        val home = temporary.newFolder().canonicalFile
        val legacy = File(home, "workspace").apply { mkdir() }
        File(legacy, "keep.txt").writeText("original")
        val store = WorkspaceStore(home)
        var initialized: File? = null
        val a = store.create("Project A") { initialized = it; File(it, ".git").mkdir() }
        val b = store.create("Project B") { File(it, ".git").mkdir() }
        assertEquals(initialized, store.resolve(a.ref))
        assertNotEquals(store.resolve(a.ref), store.resolve(b.ref))
        File(store.resolve(a.ref)!!, "result.txt").writeText("A")
        assertFalse(File(store.resolve(b.ref)!!, "result.txt").exists())
        assertEquals("original", File(legacy, "keep.txt").readText())
        assertEquals(listOf("默认本机工作区", "Project A", "Project B").toSet(), WorkspaceStore(home).list().map { it.name }.toSet())
    }
    @Test fun `unknown traversal and substituted directory are rejected`() = runBlocking {
        val home = temporary.newFolder().canonicalFile
        val store = WorkspaceStore(home)
        val workspace = store.create("A") { }
        assertNull(store.resolve(WorkspaceRef("../outside")))
        assertNull(store.resolve(WorkspaceRef("local-12345678-1234-1234-1234-123456789abc")))
        val directory = store.resolve(workspace.ref)!!
        directory.deleteRecursively()
        Files.createSymbolicLink(directory.toPath(), temporary.newFolder().toPath())
        assertNull(store.resolve(workspace.ref))
        assertTrue(store.list().isEmpty())
    }
    @Test fun `duplicate display names do not create ambiguous directories`() = runBlocking {
        val home = temporary.newFolder()
        WorkspaceStore(home).create("A") { }
        try { WorkspaceStore(home).create(" A ") { fail("must not initialize") }; fail("must reject duplicate") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, WorkspaceStore(home).list().size)
    }
    @Test fun `failed initialization is not offered as a usable workspace`() = runBlocking {
        val home = temporary.newFolder()
        val store = WorkspaceStore(home)
        try { store.create("A") { error("git failed") }; fail("must fail") } catch (_: IllegalStateException) { }
        assertTrue(store.list().isEmpty())
        assertTrue(File(home, "workspaces").listFiles()!!.none { it.name.startsWith("local-") })
        assertTrue(File(home, "workspaces/.names").listFiles()!!.isEmpty())
    }
}
