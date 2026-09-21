package com.mobby.runtime.android

import com.mobby.runtime.api.*
import com.mobby.runtime.engine.StopCause
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ResourceQuotaServiceTest {
    internal class FixtureService : RuntimeService() { override suspend fun stopForHost(cause: StopCause) {} }
    @Test fun `import reports attachment budget exhaustion separately from disk failure without deleting content`() = runBlocking {
        val controller = Robolectric.buildService(FixtureService::class.java)
        val service = controller.get()
        service.getSharedPreferences("runtime-storage-policy", 0).edit().clear().commit()
        val root = File(service.filesDir, "input-resources").apply { mkdirs() }
        val existing = File(root, "quota-fixture")
        RandomAccessFile(existing, "rw").use { it.setLength(ResourceStore.DEFAULT_BUDGET_BYTES) }
        try {
            assertEquals(AdminResult.Failed(RuntimeError(ErrorCode.RESOURCE_BUDGET_EXCEEDED)),
                service.importResource(ImportResourceRequest(WorkspaceRef("default"), "new.txt", "fixture".toByteArray())))
            assertEquals(ResourceStore.DEFAULT_BUDGET_BYTES, existing.length())
            assertEquals(listOf(existing.name), root.listFiles()!!.map { it.name })
        } finally { existing.delete(); root.delete(); controller.destroy() }
    }
}
