package com.github.ytlog.mobby.android.conversation.ui

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseAssetsTest {
    private fun notices(name: String) = RuntimeEnvironment.getApplication().assets.open("third-party/$name.json")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray.map { row -> row.jsonObject } }

    @Test fun distributedAssetsContainOriginalPermissionsCopyrightAndNotice() {
        val maven = notices("maven")
        val ids = maven.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(113, maven.size)
        val autolink = maven.single { it.getValue("id").jsonPrimitive.content == "org.nibor.autolink:autolink:0.12.0" }
        assertTrue(autolink.getValue("text").jsonPrimitive.content.contains("Copyright (c)"))
        assertTrue(autolink.getValue("text").jsonPrimitive.content.contains("Permission is hereby granted"))
        val sherpa = maven.single { it.getValue("id").jsonPrimitive.content.contains("sherpa-onnx") }
        assertTrue(sherpa.getValue("text").jsonPrimitive.content.contains("Copyright (c) Microsoft Corporation"))
        assertTrue(sherpa.getValue("text").jsonPrimitive.content.contains("ThirdPartyNotices.txt"))
        val runtime = notices("runtime")
        assertTrue(runtime.any { it.getValue("id").jsonPrimitive.content.endsWith("JavaScriptCore-COPYING.LIB") })
        assertTrue(runtime.any { it.getValue("id").jsonPrimitive.content.endsWith("bubblewrap-COPYING") })
        assertTrue(runtime.any { it.getValue("id").jsonPrimitive.content == "share/LICENSES/GPL-3.0.txt" })
        assertFalse(runtime.any { it.getValue("id").jsonPrimitive.content == "share/LICENSES/AGPL-V3.txt" })
        assertFalse(runtime.any { it.getValue("id").jsonPrimitive.content.contains("@anthropic-ai/claude-code/") })
        val rustCrypto = runtime.single { it.getValue("id").jsonPrimitive.content == "codex-dependency:aws-lc-sys:0.39.0" }
        assertTrue(rustCrypto.getValue("text").jsonPrimitive.content.contains("Copyright"))
        val bunPolyfill = runtime.single { it.getValue("id").jsonPrimitive.content == "bun-dependency:hmac-drbg:1.0.1" }
        assertTrue(bunPolyfill.getValue("text").jsonPrimitive.content.contains("Copyright Fedor Indutny"))
        assertTrue(bunPolyfill.getValue("text").jsonPrimitive.content.contains("Permission is hereby granted"))
        val openCodeDependency = runtime.single { it.getValue("id").jsonPrimitive.content == "opencode-dependency:ws:8.21.0" }
        assertTrue(openCodeDependency.getValue("text").jsonPrimitive.content.contains("Permission is hereby granted"))
        assertFalse(runtime.any { it.getValue("id").jsonPrimitive.content.startsWith("opencode-dependency:type-fest:") })
        val kleidi = runtime.single { it.getValue("id").jsonPrimitive.content == "share/mobby/licenses/KleidiAI-NOTICE" }
        assertTrue(kleidi.getValue("text").jsonPrimitive.content.contains("Copyright 2025-2026 Arm Limited"))
        val androidCpp = runtime.single { it.getValue("id").jsonPrimitive.content == "share/mobby/licenses/Android-libc++-LICENSE" }
        assertTrue(androidCpp.getValue("text").jsonPrimitive.content.contains("LLVM"))
    }
}
