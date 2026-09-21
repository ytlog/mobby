package com.mobby.runtime.android

import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class AgentInputFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `CLI input contains exact bytes and protocol framing then is removed`() {
        val root = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3, -1)
        val images = listOf(ResourceStore.Image("private name.png", "image/png", bytes))
        AgentInputFiles.create(root, images).use { input ->
            assertArrayEquals(bytes, File(input.imagePaths.single()).readBytes())
        }
        assertTrue(root.listFiles()!!.isEmpty())
        val message = AgentInputFiles.claudeMessage("literal \"prompt\"\n中文", images).getValue("message").jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        val content = message.getValue("content").jsonArray
        assertEquals("literal \"prompt\"\n中文", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        val source = content[1].jsonObject.getValue("source").jsonObject
        assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
        assertArrayEquals(bytes, Base64.getDecoder().decode(source.getValue("data").jsonPrimitive.content))
    }
    @Test fun `failed or interrupted scope and startup cleanup leave no temporary input and never follow links`() {
        val root = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            AgentInputFiles.create(root, emptyList()).use { error("cancelled") }
        }
        assertTrue(root.listFiles()!!.isEmpty())
        val external = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "link").toPath(), external.toPath())
        AgentInputFiles.create(root, emptyList())
        AgentInputFiles.cleanup(root)
        assertFalse(root.exists()); assertEquals("keep", external.readText())
    }
}
