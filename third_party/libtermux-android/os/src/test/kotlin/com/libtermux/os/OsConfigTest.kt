/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.os

import com.libtermux.os.distro.Distro
import org.junit.Assert.*
import org.junit.Test

class OsConfigTest {

    @Test
    fun `default OsConfig has expected safe defaults`() {
        val config = OsConfig()
        assertTrue(config.registry.isEmpty())
        assertEquals(ExecutionMode.AUTO, config.executionMode)
        assertNull(config.distroStorageDir)
        assertEquals(600_000L, config.downloadTimeoutMs)
        assertTrue(config.bindSdCard)
        assertTrue(config.fakeRoot)
        assertTrue(config.extraBindMounts.isEmpty())
        assertTrue(config.runSetupCommands)
        assertTrue(config.configureDns)
        assertEquals("1.1.1.1", config.dnsServer)
    }

    @Test
    fun `OsConfigDsl build with no changes matches OsConfig defaults`() {
        val built = OsConfigDsl().build()
        val default = OsConfig()
        assertEquals(default.executionMode, built.executionMode)
        assertEquals(default.bindSdCard, built.bindSdCard)
        assertEquals(default.fakeRoot, built.fakeRoot)
        assertEquals(default.runSetupCommands, built.runSetupCommands)
        assertEquals(default.configureDns, built.configureDns)
        assertEquals(default.dnsServer, built.dnsServer)
        assertEquals(default.downloadTimeoutMs, built.downloadTimeoutMs)
        assertTrue(built.registry.isEmpty())
    }

    @Test
    fun `bind adds hostPath colon containerPath entry`() {
        val built = OsConfigDsl().apply {
            bind("/sdcard/Download", "/mnt/download")
        }.build()

        assertEquals(listOf("/sdcard/Download:/mnt/download"), built.extraBindMounts)
    }

    @Test
    fun `bind with single argument mounts hostPath at the same containerPath`() {
        val built = OsConfigDsl().apply {
            bind("/sdcard")
        }.build()

        assertEquals(listOf("/sdcard:/sdcard"), built.extraBindMounts)
    }

    @Test
    fun `multiple bind calls accumulate in order`() {
        val built = OsConfigDsl().apply {
            bind("/a")
            bind("/b", "/mnt/b")
        }.build()

        assertEquals(listOf("/a:/a", "/b:/mnt/b"), built.extraBindMounts)
    }

    @Test
    fun `registry block populates the built config's registry`() {
        val built = OsConfigDsl().apply {
            registry {
                distro(Distro.Kali) { guiEnabled = true }
            }
        }.build()

        assertFalse(built.registry.isEmpty())
        assertTrue(built.registry.supports(Distro.Kali))
        assertTrue(built.registry.hasGui(Distro.Kali))
    }

    @Test
    fun `overriding scalar fields is reflected in the built OsConfig`() {
        val built = OsConfigDsl().apply {
            executionMode = ExecutionMode.REAL_CHROOT
            bindSdCard = false
            fakeRoot = false
            runSetupCommands = false
            configureDns = false
            dnsServer = "8.8.8.8"
            downloadTimeoutMs = 60_000L
        }.build()

        assertEquals(ExecutionMode.REAL_CHROOT, built.executionMode)
        assertFalse(built.bindSdCard)
        assertFalse(built.fakeRoot)
        assertFalse(built.runSetupCommands)
        assertFalse(built.configureDns)
        assertEquals("8.8.8.8", built.dnsServer)
        assertEquals(60_000L, built.downloadTimeoutMs)
    }
}
