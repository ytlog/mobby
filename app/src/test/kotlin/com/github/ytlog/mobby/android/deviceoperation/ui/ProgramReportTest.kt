package com.github.ytlog.mobby.android.deviceoperation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramReportTest {
    @get:Rule val compose = createComposeRule()

    private fun report(data: JsonObject) = DeviceOperation("operation", "request", 1, "programs", "dry-run", "basic",
        DeviceStatus.SUCCEEDED, "programs.dry-run", DeviceSubject(), buildJsonObject { put("path", "~/workspace/programs/header-watch") },
        result = DeviceResult("program_report", EffectState.NONE, data))

    @Test fun `validation results list problems and warnings`() {
        assertEquals(listOf("校验通过：header-watch 1.0.0，4 个文件", "提示：uses global fetch"), programReportLines(buildJsonObject {
            put("valid", true); put("id", "header-watch"); put("version", "1.0.0"); put("files", 4)
            put("warnings", JsonArray(listOf(JsonPrimitive("uses global fetch"))))
        }))
        assertEquals(listOf("校验未通过", "manifest.json: id is required"), programReportLines(buildJsonObject {
            put("valid", false); put("problems", JsonArray(listOf(JsonPrimitive("manifest.json: id is required"))))
        }))
    }

    @Test fun `dry run results show each call and log instead of an unsupported notice`() {
        val data = buildJsonObject {
            put("ran", true); put("outcome", "exited"); put("reason", "idle"); put("timedOut", false)
            put("calls", JsonArray(listOf(
                buildJsonObject { put("capability", "net.http"); put("status", "simulated"); put("effect", "none") },
                buildJsonObject { put("capability", "storage.sql"); put("status", "failed"); put("effect", "none"); put("error", "INVALID_ARGUMENT"); put("reason", "sql_error: no such table") },
            )))
            put("logs", JsonArray(listOf(buildJsonObject { put("message", "handler_failed"); put("data", "{\"handler\":\"onSchedule\"}") })))
        }
        assertEquals(listOf("试运行结束：exited · idle", "net.http · simulated", "storage.sql · failed · INVALID_ARGUMENT: sql_error: no such table",
            "日志：handler_failed {\"handler\":\"onSchedule\"}"), programReportLines(data))
        compose.setContent { MaterialTheme { BasicDeviceCard(report(data), expanded = true) } }
        compose.onNodeWithText("net.http · simulated").assertIsDisplayed()
        compose.onNodeWithText("此结果类型尚不支持展示，可展开查看记录。").assertDoesNotExist()
    }

    @Test fun `a dry run that could not start says why`() {
        assertEquals(listOf("试运行未开始", "no background logic to run"), programReportLines(buildJsonObject {
            put("ran", false); put("problems", JsonArray(listOf(JsonPrimitive("no background logic to run"))))
        }))
        assertEquals(listOf("记录：2 次运行，0 次调用，1 条日志"), programReportLines(buildJsonObject {
            put("runs", JsonArray(listOf(JsonObject(emptyMap()), JsonObject(emptyMap())))); put("calls", JsonArray(emptyList()))
            put("logs", JsonArray(listOf(JsonObject(emptyMap()))))
        }))
    }
}
