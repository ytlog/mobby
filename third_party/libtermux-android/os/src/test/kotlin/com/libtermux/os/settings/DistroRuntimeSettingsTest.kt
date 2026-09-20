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
package com.libtermux.os.settings

import com.libtermux.os.registry.DisplayResolution
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers only the pure logic on [DistroRuntimeSettings]. [DistroSettingsStore]
 * itself needs an Android [android.content.Context] + DataStore (Robolectric),
 * which this module does not yet wire up — see os/build.gradle.kts.
 */
class DistroRuntimeSettingsTest {

    @Test
    fun `default values match declared defaults`() {
        val s = DistroRuntimeSettings(distroId = "kali")
        assertEquals(1280, s.displayWidth)
        assertEquals(720, s.displayHeight)
        assertEquals(24, s.colorDepth)
        assertEquals(5901, s.vncPort)
        assertEquals("", s.vncPassword)
        assertTrue(s.startupCmds.isEmpty())
        assertTrue(s.showToolbar)
        assertTrue(s.scaleToFit)
        assertFalse(s.vibrateMouse)
    }

    @Test
    fun `resolutionLabel formats width x height with multiplication sign`() {
        val s = DistroRuntimeSettings(distroId = "kali", displayWidth = 1920, displayHeight = 1080)
        assertEquals("1920\u00D71080", s.resolutionLabel)
    }

    @Test
    fun `matchesPreset is true when dimensions match a non-custom preset`() {
        val s = DistroRuntimeSettings(distroId = "kali", displayWidth = 1920, displayHeight = 1080)
        assertTrue(s.matchesPreset(DisplayResolution.FHD_1080P))
    }

    @Test
    fun `matchesPreset is false when dimensions differ from the preset`() {
        val s = DistroRuntimeSettings(distroId = "kali", displayWidth = 1280, displayHeight = 720)
        assertFalse(s.matchesPreset(DisplayResolution.FHD_1080P))
    }

    @Test
    fun `matchesPreset is always false for CUSTOM regardless of dimensions`() {
        val s = DistroRuntimeSettings(distroId = "kali", displayWidth = -1, displayHeight = -1)
        assertFalse(s.matchesPreset(DisplayResolution.CUSTOM))
    }
}
