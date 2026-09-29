package com.github.ytlog.mobby.android.runtime.android

import android.net.Uri
import com.github.ytlog.mobby.android.device.DeviceStorage
import com.github.ytlog.mobby.android.runtime.api.AdminResult
import com.github.ytlog.mobby.android.runtime.api.ErrorCode
import com.github.ytlog.mobby.android.runtime.api.RuntimeError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceDirectoryGrantTest {
    @Test fun `directory picked through the admin API persists and a replacement releases the old grant`() = runBlocking {
        // Do not start the native runtime: this admin operation only owns Android URI authorization.
        val service = Robolectric.buildService(RuntimeService::class.java).get()
        val first = Uri.parse("content://fixture.documents/tree/first")
        val next = Uri.parse("content://fixture.documents/tree/next")
        assertEquals(AdminResult.Success(Unit), service.saveDeviceDirectory(first.toString()))
        assertEquals(first, DeviceStorage.tree(service))
        assertEquals(AdminResult.Success(Unit), service.saveDeviceDirectory(next.toString()))
        assertEquals(next, DeviceStorage.tree(service))
        val grants = service.contentResolver.persistedUriPermissions
        assertTrue(grants.any { it.uri == next && it.isReadPermission && it.isWritePermission })
        assertFalse(grants.any { it.uri == first })
    }

    @Test fun `paths and document URIs are rejected without replacing the authorized directory`() = runBlocking {
        val service = Robolectric.buildService(RuntimeService::class.java).get()
        val original = Uri.parse("content://fixture.documents/tree/original")
        assertEquals(AdminResult.Success(Unit), service.saveDeviceDirectory(original.toString()))
        for (location in listOf("/storage/emulated/0", "file:///storage/emulated/0", "content://fixture.documents/document/file")) {
            assertEquals(AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)), service.saveDeviceDirectory(location))
            assertEquals(original, DeviceStorage.tree(service))
        }
    }
}
