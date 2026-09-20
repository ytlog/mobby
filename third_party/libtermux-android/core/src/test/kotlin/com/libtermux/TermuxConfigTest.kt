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

import org.junit.Assert.*
import org.junit.Test

class TermuxConfigTest {

    @Test
    fun `default config has correct values`() {
        val config = TermuxConfig.default()
        assertEquals(Architecture.AUTO, config.architecture)
        assertTrue(config.autoInstall)
        assertEquals(LogLevel.INFO, config.logLevel)
        assertEquals(30_000L, config.maxCommandTimeoutMs)
        assertTrue(config.environmentVariables.isEmpty())
    }

    @Test
    fun `DSL config builder works correctly`() {
        val config = termuxConfig {
            architecture = Architecture.ARM64
            logLevel     = LogLevel.DEBUG
            autoInstall  = false
            env("MY_KEY", "MY_VALUE")
            maxCommandTimeoutMs = 60_000L
        }
        assertEquals(Architecture.ARM64, config.architecture)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertFalse(config.autoInstall)
        assertEquals("MY_VALUE", config.environmentVariables["MY_KEY"])
        assertEquals(60_000L, config.maxCommandTimeoutMs)
    }

    @Test
    fun `Java builder produces correct config`() {
        val config = TermuxConfig.builder()
            .architecture(Architecture.X86_64)
            .logLevel(LogLevel.VERBOSE)
            .maxCommandTimeoutMs(5_000L)
            .addEnv("K", "V")
            .build()
        assertEquals(Architecture.X86_64, config.architecture)
        assertEquals(LogLevel.VERBOSE, config.logLevel)
        assertEquals(5_000L, config.maxCommandTimeoutMs)
        assertEquals("V", config.environmentVariables["K"])
    }

    @Test
    fun `bootstrap URL is formatted correctly`() {
        val url = TermuxConfig.bootstrapUrl(Architecture.ARM64, TermuxConfig.LATEST_BOOTSTRAP)
        assertTrue(url.contains("aarch64"))
        assertTrue(url.startsWith("https://"))
        assertTrue(url.endsWith(".zip"))
    }

    @Test
    fun `custom bootstrap URL overrides default`() {
        val config = termuxConfig {
            customBootstrapUrl = "https://my.server.com/bootstrap.zip"
        }
        assertEquals("https://my.server.com/bootstrap.zip", config.customBootstrapUrl)
    }

    @Test
    fun `multiple env vars can be added`() {
        val config = termuxConfig {
            env("A", "1")
            env("B", "2")
            env("C", "3")
        }
        assertEquals(3, config.environmentVariables.size)
        assertEquals("1", config.environmentVariables["A"])
        assertEquals("2", config.environmentVariables["B"])
        assertEquals("3", config.environmentVariables["C"])
    }
}
