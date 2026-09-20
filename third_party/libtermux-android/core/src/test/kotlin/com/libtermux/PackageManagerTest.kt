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

import com.libtermux.executor.CommandExecutor
import com.libtermux.executor.ExecutionResult
import com.libtermux.pkg.PackageManager
import com.libtermux.pkg.PkgResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PackageManagerTest {

    private lateinit var executor: CommandExecutor
    private lateinit var pm: PackageManager

    private fun okResult(stdout: String = "") =
        ExecutionResult(stdout, "", 0, 0L, "test")

    private fun failResult(stderr: String = "error") =
        ExecutionResult("", stderr, 1, 0L, "test")

    @Before
    fun setup() {
        executor = mockk()
        pm = PackageManager(executor)
    }

    @Test
    fun `install returns Success on exit 0`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns okResult("pkg installed ok")
        val result = pm.install("python")
        assertTrue(result is PkgResult.Success)
    }

    @Test
    fun `install returns NotFound when package not found`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            failResult("Unable to locate package python999")
        val result = pm.install("python999")
        assertTrue(result is PkgResult.NotFound)
    }

    @Test
    fun `install returns AlreadyInstalled when already present`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            failResult("already installed")
        val result = pm.install("curl")
        assertTrue(result is PkgResult.AlreadyInstalled)
    }

    @Test
    fun `install returns Failed on generic error`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns
            failResult("network error")
        val result = pm.install("wget")
        assertTrue(result is PkgResult.Failed)
        assertEquals(1, (result as PkgResult.Failed).exitCode)
    }

    @Test
    fun `isInstalled returns true when dpkg shows package`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns okResult("1")
        assertTrue(pm.isInstalled("curl"))
    }

    @Test
    fun `isInstalled returns false when not installed`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns okResult("0")
        assertFalse(pm.isInstalled("nonexistent"))
    }

    @Test
    fun `upgradeAll returns Success on exit 0`() = runTest {
        coEvery { executor.execute(any(), any(), any(), any()) } returns okResult("upgraded")
        val result = pm.upgradeAll()
        assertTrue(result is PkgResult.Success)
    }
}
