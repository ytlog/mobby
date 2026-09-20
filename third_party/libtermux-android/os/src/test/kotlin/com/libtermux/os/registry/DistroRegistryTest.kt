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
package com.libtermux.os.registry

import com.libtermux.os.distro.Distro
import org.junit.Assert.*
import org.junit.Test

class DistroRegistryTest {

    @Test
    fun `empty registry reports isEmpty and size zero`() {
        val registry = DistroRegistry()
        assertTrue(registry.isEmpty())
        assertEquals(0, registry.size())
        assertTrue(registry.all.isEmpty())
    }

    @Test
    fun `distro with DSL block registers and is retrievable`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali) {
            guiEnabled = true
            desktopEnvironment = DesktopEnvironment.XFCE4
        }

        assertFalse(registry.isEmpty())
        assertEquals(1, registry.size())
        assertTrue(registry.supports(Distro.Kali))

        val entry = registry[Distro.Kali]
        assertNotNull(entry)
        assertTrue(entry!!.guiEnabled)
        assertEquals(DesktopEnvironment.XFCE4, entry.desktopEnvironment)
    }

    @Test
    fun `distro with pre-built SupportedDistro registers correctly`() {
        val registry = DistroRegistry()
        val built = SupportedDistroBuilder(Distro.Alpine).apply {
            guiEnabled = false
        }.build()

        registry.distro(built)

        assertTrue(registry.supports(Distro.Alpine))
        assertEquals(built, registry[Distro.Alpine])
    }

    @Test
    fun `get returns null for unregistered distro`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali)
        assertNull(registry[Distro.Ubuntu2404])
        assertFalse(registry.supports(Distro.Ubuntu2404))
    }

    @Test
    fun `registering same distro id twice overwrites the previous entry`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali) { guiEnabled = false }
        registry.distro(Distro.Kali) { guiEnabled = true }

        assertEquals(1, registry.size())
        assertTrue(registry[Distro.Kali]!!.guiEnabled)
    }

    @Test
    fun `hasGui reflects guiEnabled flag per distro`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali) { guiEnabled = true }
        registry.distro(Distro.Ubuntu2404) { guiEnabled = false }

        assertTrue(registry.hasGui(Distro.Kali))
        assertFalse(registry.hasGui(Distro.Ubuntu2404))
        // Never-registered distro is also "no GUI"
        assertFalse(registry.hasGui(Distro.Alpine))
    }

    @Test
    fun `launcherEntries excludes distros with showInLauncher false`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali) { showInLauncher = true }
        registry.distro(Distro.Ubuntu2404) { showInLauncher = false }

        val launcherIds = registry.launcherEntries.map { it.distro.id }
        assertTrue("kali" in launcherIds)
        assertFalse("ubuntu-24.04" in launcherIds)
    }

    @Test
    fun `guiDistros only includes distros with guiEnabled true`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali) { guiEnabled = true }
        registry.distro(Distro.Ubuntu2404) { guiEnabled = false }
        registry.distro(Distro.Alpine) { guiEnabled = true }

        val guiIds = registry.guiDistros.map { it.distro.id }.toSet()
        assertEquals(setOf("kali", "alpine"), guiIds)
    }

    @Test
    fun `distro registration returns the registry for chaining`() {
        val registry = DistroRegistry()
        val result = registry.distro(Distro.Kali).distro(Distro.Alpine)
        assertSame(registry, result)
        assertEquals(2, registry.size())
    }

    @Test
    fun `all preserves insertion order`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Fedora40)
        registry.distro(Distro.Alpine)
        registry.distro(Distro.Kali)

        assertEquals(
            listOf("fedora-40", "alpine", "kali"),
            registry.all.map { it.distro.id },
        )
    }

    @Test
    fun `DistroRegistryBuilder builds an equivalent registry`() {
        val built = DistroRegistryBuilder().apply {
            distro(Distro.Kali) { guiEnabled = true }
            distro(Distro.Ubuntu2404)
        }.build()

        assertEquals(2, built.size())
        assertTrue(built.supports(Distro.Kali))
        assertTrue(built.supports(Distro.Ubuntu2404))
        assertTrue(built.hasGui(Distro.Kali))
    }

    @Test
    fun `toString includes registered distro ids`() {
        val registry = DistroRegistry()
        registry.distro(Distro.Kali)
        registry.distro(Distro.Alpine)

        val str = registry.toString()
        assertTrue(str.contains("kali"))
        assertTrue(str.contains("alpine"))
    }
}
