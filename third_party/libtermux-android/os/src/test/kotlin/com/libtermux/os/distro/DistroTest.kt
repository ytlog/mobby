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
package com.libtermux.os.distro

import org.junit.Assert.*
import org.junit.Test

class DistroTest {

    @Test
    fun `all contains exactly the six built-in distros`() {
        val ids = Distro.all.map { it.id }.toSet()
        assertEquals(
            setOf("kali", "ubuntu-24.04", "ubuntu-22.04", "debian-12", "alpine", "fedora-40"),
            ids,
        )
        assertEquals(6, Distro.all.size)
    }

    @Test
    fun `fromId finds a built-in distro by its id`() {
        assertEquals(Distro.Kali, Distro.fromId("kali"))
        assertEquals(Distro.Alpine, Distro.fromId("alpine"))
        assertEquals(Distro.Ubuntu2404, Distro.fromId("ubuntu-24.04"))
    }

    @Test
    fun `fromId returns null for an unknown id`() {
        assertNull(Distro.fromId("arch-linux"))
        assertNull(Distro.fromId(""))
    }

    @Test
    fun `Custom distro exposes constructor fields via base Distro properties`() {
        val custom = Distro.Custom(
            customId = "my-distro",
            customName = "My Distro",
            url = "https://example.com/rootfs.tar.gz",
            checksumUrl = "https://example.com/rootfs.tar.gz.sha256",
            compressionType = CompressionType.GZ,
            shell = "/bin/ash",
        )

        assertEquals("my-distro", custom.id)
        assertEquals("My Distro", custom.displayName)
        assertEquals("https://example.com/rootfs.tar.gz", custom.rootfsUrl)
        assertEquals("https://example.com/rootfs.tar.gz.sha256", custom.sha256Url)
        assertEquals(CompressionType.GZ, custom.compression)
        assertEquals("/bin/ash", custom.defaultShell)
    }

    @Test
    fun `Custom distro is not part of the built-in all list`() {
        val custom = Distro.Custom("x", "X", "https://example.com/x.tar.gz")
        assertFalse(custom in Distro.all)
        assertNull(Distro.fromId("x"))
    }

    @Test
    fun `each built-in distro has a non-blank rootfsUrl matching its CompressionType extension`() {
        Distro.all.forEach { distro ->
            assertTrue("${distro.id} rootfsUrl should not be blank", distro.rootfsUrl.isNotBlank())
            assertTrue(
                "${distro.id} rootfsUrl should end with ${distro.compression.extension}",
                distro.rootfsUrl.endsWith(distro.compression.extension),
            )
        }
    }

    @Test
    fun `Alpine uses sh as default shell, others use bash`() {
        assertEquals("/bin/sh", Distro.Alpine.defaultShell)
        assertEquals("/bin/bash", Distro.Kali.defaultShell)
        assertEquals("/bin/bash", Distro.Ubuntu2404.defaultShell)
        assertEquals("/bin/bash", Distro.Ubuntu2204.defaultShell)
        assertEquals("/bin/bash", Distro.Debian12.defaultShell)
        assertEquals("/bin/bash", Distro.Fedora40.defaultShell)
    }
}
