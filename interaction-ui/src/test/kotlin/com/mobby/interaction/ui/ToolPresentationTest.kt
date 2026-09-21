package com.mobby.interaction.ui

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ToolPresentationTest {
    @Test fun `bash json becomes a command title instead of raw payload`() {
        val view = ToolPresentation.step("command_execution", """{"command":"ls -la","workdir":"/tmp"}""", """{"output":"file.txt"}""")
        assertEquals("运行 ls -la", view.title)
        assertFalse(view.detail.contains("{\"command\""))
        assertTrue(view.detail.contains("ls -la"))
        assertTrue(view.terminal)
    }

    @Test fun `permission scope shows human fields not the original json`() {
        val view = ToolPresentation.permission("Write", """{"file_path":"/fixture/file","content":"literal"}""")
        assertEquals("写入 /fixture/file", view.title)
        assertEquals("文件\n/fixture/file\n\n内容\nliteral", view.detail)
        assertFalse(view.detail.contains("file_path"))
    }
}
