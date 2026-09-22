package com.github.ytlog.mobby.android.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DevicePluginTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `bridge allowlist rejects actions from plugins that were not enabled`() {
        val allow = DeviceCatalog.allow(setOf("plugin:device:sms"))
        val denied = json(DeviceCommands.handle("""{"token":"ok","plugin":"camera","action":"photo","args":{}}""", "ok", allow) { _, _, _ -> error("should not run") })
        assertFalse(denied["ok"]!!.jsonPrimitive.boolean)
        assertEquals("未授权", denied["error"]!!.jsonPrimitive.content)
        val wrongToken = json(DeviceCommands.handle("""{"token":"no","plugin":"sms","action":"list","args":{}}""", "ok", allow) { _, _, _ -> error("should not run") })
        assertEquals("未授权", wrongToken["error"]!!.jsonPrimitive.content)
        val listed = json(DeviceCommands.handle("""{"token":"ok","plugin":"sms","action":"list","args":{}}""", "ok", allow) { plugin, action, _ -> "$plugin:$action" })
        assertEquals("sms:list", listed["result"]!!.jsonPrimitive.content)
        val send = json(DeviceCommands.handle("""{"token":"ok","plugin":"sms","action":"send","args":{"to":"1"}}""", "ok", allow) { _, _, _ -> error("should not run") })
        assertEquals("未授权", send["error"]!!.jsonPrimitive.content)
    }

    @Test fun `write grant is absent until its own ref is enabled`() {
        assertTrue(DeviceCatalog.missingParent(setOf("plugin:device:sms:send")))
        assertFalse(DeviceCatalog.missingParent(setOf("plugin:device:sms", "plugin:device:sms:send")))
        assertEquals(setOf("list"), DeviceCatalog.allow(setOf("plugin:device:sms"))["sms"])
        assertEquals(setOf("list", "send"), DeviceCatalog.allow(setOf("plugin:device:sms", "plugin:device:sms:send"))["sms"])
        assertFalse(DeviceCatalog.isKnown("plugin:PHONE:ACCESSIBILITY"))
    }

    @Test fun `skill text includes only the actions enabled for this run`() {
        val root = temporary.newFolder()
        val skill = DeviceSkillPack.write(root, "/bin/node", 43123, """tok"en""", setOf("plugin:device:sms")).single()
        val markdown = skill.readText()
        assertTrue(markdown.contains("name: mobby-sms"))
        assertTrue(markdown.contains("list"))
        assertFalse(markdown.contains("send {"))
        val helper = File(skill.parentFile, "scripts/device.cjs").readText()
        assertTrue(helper.contains("127.0.0.1"))
        assertTrue(helper.contains("sms"))
        assertTrue(helper.contains("tok\\\"en"))
        val both = DeviceSkillPack.write(temporary.newFolder(), "/bin/node", 9, "tok", setOf("plugin:device:sms", "plugin:device:sms:send")).single().readText()
        assertTrue(both.contains("send {"))
    }

    @Test fun `office files round-trip text and stay inside the workspace`() {
        val workspace = temporary.newFolder("workspace")
        val inbox = temporary.newFolder("inbox")
        val docx = File(workspace, "notes.docx")
        OfficePackage.write(docx, "第一段\n第二段")
        assertEquals("docx 段落 2", OfficePackage.inspect(docx))
        assertEquals("第一段\n第二段", OfficePackage.read(docx))
        val xlsx = File(workspace, "sheet.xlsx")
        OfficePackage.write(xlsx, "甲\n乙")
        assertTrue(OfficePackage.read(xlsx).contains("甲"))
        val pptx = File(workspace, "deck.pptx")
        OfficePackage.write(pptx, "封面")
        assertEquals("pptx 幻灯片 1", OfficePackage.inspect(pptx))
        assertEquals("封面", OfficePackage.read(pptx))
        assertEquals(docx.canonicalFile, DevicePaths.resolve(workspace, inbox, "notes.docx"))
        assertThrows(IllegalStateException::class.java) { DevicePaths.resolve(workspace, inbox, "../secret") }
        val outside = temporary.newFile("outside.txt")
        assertThrows(IllegalStateException::class.java) { DevicePaths.resolve(workspace, inbox, outside.absolutePath) }
    }

    private fun json(line: String) = Json.parseToJsonElement(line).jsonObject
}