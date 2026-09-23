package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.domain.Step
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ToolPresentationTest {
    @Test fun `a command shows the command and its result`() {
        val view = ToolPresentation.present(Step.Command("s", "ls -la", "file.txt", "SUCCEEDED"))
        assertEquals("运行 ls -la", view.title)
        assertTrue(view.detail.contains("命令"))
        assertTrue(view.detail.contains("ls -la"))
        assertTrue(view.detail.contains("file.txt"))
        assertTrue(view.detail.contains("结果"))
        assertTrue(view.terminal)
        val silent = ToolPresentation.present(Step.Command("s", "cat > a.py << 'EOF'\nprint(1)\nEOF", "", "SUCCEEDED"))
        assertTrue(silent.detail.contains("print(1)"))
        assertTrue(silent.detail.contains("无输出"))
    }

    @Test fun `read write and edit expand to content or a diff`() {
        val read = ToolPresentation.present(Step.FileRead("s", "/tmp/a.py", "print(1)\n", "SUCCEEDED"))
        assertEquals("读取 /tmp/a.py", read.title)
        assertEquals("print(1)", read.detail.trim())
        val write = ToolPresentation.present(Step.FileWrite("s", "/tmp/a.py", "print(1)", "SUCCEEDED"))
        assertEquals("写入 /tmp/a.py", write.title)
        assertEquals("print(1)", write.detail)
        val edit = ToolPresentation.present(Step.FileDiff("s", listOf("/tmp/a.py"), "--- a.py\n+++ a.py\n- print(1)\n+ print(2)", "SUCCEEDED"))
        assertEquals("修改文件 a.py", edit.title)
        assertTrue(edit.detail.contains("- print(1)"))
        assertTrue(edit.detail.contains("+ print(2)"))
    }

    @Test fun `permission scope shows human fields not the original json`() {
        val view = ToolPresentation.permission("Write", """{"file_path":"/fixture/file","content":"literal"}""")
        assertEquals("写入 /fixture/file", view.title)
        assertEquals("文件\n/fixture/file\n\n内容\nliteral", view.detail)
        assertFalse(view.detail.contains("file_path"))
    }

    @Test fun `phone click is a screen action instead of raw protocol kind`() {
        val view = ToolPresentation.present(Step.Action("s", "click", "确定", "已点击：确定", "SUCCEEDED"))
        assertEquals("点击 确定", view.title)
        assertTrue(view.detail.contains("确定"))
        assertFalse(view.detail.contains("mcp_tool_call"))
        val snapshot = ToolPresentation.present(Step.Action("s", "mcp__phone__snapshot", "", "Launcher", "SUCCEEDED"))
        assertEquals("读取屏幕", snapshot.title)
        assertTrue(snapshot.detail.contains("Launcher"))
    }
}
