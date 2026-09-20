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

/**
 * First baseline test for the `:os` module — covers [SupportedDistro]'s
 * derived properties (vncPort, effectiveWidth/Height, isGnomeBased), which
 * are pure logic with no Android/root dependency and safe to unit test
 * without Robolectric or mocking.
 */
class SupportedDistroTest {

    private fun distro(
        desktopEnvironment: DesktopEnvironment = DesktopEnvironment.XFCE4,
        defaultResolution: DisplayResolution = DisplayResolution.HD_720P,
        customWidth: Int = 1280,
        customHeight: Int = 720,
        vncDisplay: Int = 1,
    ) = SupportedDistro(
        distro = Distro.Ubuntu2404,
        desktopEnvironment = desktopEnvironment,
        defaultResolution = defaultResolution,
        customWidth = customWidth,
        customHeight = customHeight,
        vncDisplay = vncDisplay,
    )

    @Test
    fun `vncPort is 5900 plus vncDisplay`() {
        assertEquals(5901, distro(vncDisplay = 1).vncPort)
        assertEquals(5905, distro(vncDisplay = 5).vncPort)
    }

    @Test
    fun `effectiveWidth and Height use preset resolution when not custom`() {
        val d = distro(defaultResolution = DisplayResolution.FHD_1080P)
        assertEquals(1920, d.effectiveWidth)
        assertEquals(1080, d.effectiveHeight)
    }

    @Test
    fun `effectiveWidth and Height fall back to custom values when resolution is CUSTOM`() {
        val d = distro(
            defaultResolution = DisplayResolution.CUSTOM,
            customWidth = 1600,
            customHeight = 900,
        )
        assertEquals(1600, d.effectiveWidth)
        assertEquals(900, d.effectiveHeight)
    }

    @Test
    fun `isGnomeBased is true for all GNOME family desktop environments`() {
        assertTrue(distro(DesktopEnvironment.GNOME).isGnomeBased)
        assertTrue(distro(DesktopEnvironment.GNOME_FLASHBACK).isGnomeBased)
        assertTrue(distro(DesktopEnvironment.GNOME_CLASSIC).isGnomeBased)
    }

    @Test
    fun `isGnomeBased is false for non-GNOME desktop environments`() {
        assertFalse(distro(DesktopEnvironment.XFCE4).isGnomeBased)
        assertFalse(distro(DesktopEnvironment.OPENBOX).isGnomeBased)
        assertFalse(distro(DesktopEnvironment.NONE).isGnomeBased)
    }

    @Test
    fun `builder produces same defaults as direct constructor`() {
        val built = SupportedDistroBuilder(Distro.Alpine).build()
        assertEquals(Distro.Alpine, built.distro)
        assertEquals(DesktopEnvironment.XFCE4, built.desktopEnvironment)
        assertEquals(DisplayResolution.HD_720P, built.defaultResolution)
        assertEquals(Distro.Alpine.displayName, built.description)
    }

    @Test
    fun `builder respects overridden values`() {
        val built = SupportedDistroBuilder(Distro.Kali).apply {
            guiEnabled = true
            desktopEnvironment = DesktopEnvironment.GNOME_FLASHBACK
            vncDisplay = 3
        }.build()

        assertTrue(built.guiEnabled)
        assertEquals(DesktopEnvironment.GNOME_FLASHBACK, built.desktopEnvironment)
        assertEquals(5903, built.vncPort)
    }
}
