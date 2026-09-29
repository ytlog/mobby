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
        assertFalse(runtime.any { it.getValue("id").jsonPrimitive.content.contains("@anthropic-ai/claude-code/") })
    }
}
