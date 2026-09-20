/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * * http://www.apache.org/licenses/LICENSE-2.0
 * * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux

import android.content.Context
import com.libtermux.fs.VirtualFileSystem
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VirtualFileSystemTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var vfs: VirtualFileSystem
    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk {
            every { filesDir } returns tmpFolder.root
        }
        vfs = VirtualFileSystem(context, TermuxConfig.default())
    }

    @Test
    fun `directories are created on init`() {
        assertTrue(vfs.prefixDir.exists())
        assertTrue(vfs.homeDir.exists())
        assertTrue(vfs.tmpDir.exists())
        assertTrue(vfs.binDir.exists())
        assertTrue(vfs.libDir.exists())
        assertTrue(vfs.etcDir.exists())
    }

    @Test
    fun `bootstrap marker starts unset`() {
        assertFalse(vfs.isBootstrapInstalled)
    }

    @Test
    fun `bootstrap marker can be set and cleared`() {
        vfs.markBootstrapInstalled()
        assertTrue(vfs.isBootstrapInstalled)
        vfs.clearBootstrapMarker()
        assertFalse(vfs.isBootstrapInstalled)
    }

    @Test
    fun `buildEnv contains required keys`() {
        val env = vfs.buildEnv()
        assertNotNull(env["HOME"])
        assertNotNull(env["PREFIX"])
        assertNotNull(env["TMPDIR"])
        assertNotNull(env["TERM"])
        assertNotNull(env["PATH"])
        assertNotNull(env["LANG"])
    }

    @Test
    fun `buildEnv merges extra variables`() {
        val env = vfs.buildEnv(mapOf("CUSTOM_VAR" to "custom_value"))
        assertEquals("custom_value", env["CUSTOM_VAR"])
    }

    @Test
    fun `writeHomeFile creates file with content`() {
        val file = vfs.writeHomeFile("test.txt", "hello world")
        assertTrue(file.exists())
        assertEquals("hello world", file.readText())
    }

    @Test
    fun `writeScript creates executable file in bin`() {
        val script = vfs.writeScript("myscript.sh", "#!/bin/bash\necho hi")
        assertTrue(script.exists())
        assertTrue(script.canExecute())
        assertTrue(script.parent == vfs.binDir.absolutePath)
    }

    @Test
    fun `resetAll clears and recreates directories`() {
        vfs.markBootstrapInstalled()
        vfs.resetAll()
        assertFalse(vfs.isBootstrapInstalled)
        assertTrue(vfs.prefixDir.exists())
        assertTrue(vfs.homeDir.exists())
    }

    @Test
    fun `diskUsageString returns human-readable string`() {
        val usage = vfs.diskUsageString()
        assertTrue(usage.endsWith("B") || usage.endsWith("KB") || usage.endsWith("MB"))
    }

    @Test
    fun `prefixPath resolves correctly`() {
        val file = vfs.prefixPath("bin/bash")
        assertEquals(File(vfs.prefixDir, "bin/bash").absolutePath, file.absolutePath)
    }
}
