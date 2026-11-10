package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.DeviceStatus
import org.junit.Assert.*
import org.junit.Test

class PrivateScreenInputTest {
    @Test fun `the user can choose takeover instead of submitting the private form`() {
        val execution = DeviceExecution("operation") { _, _, attention ->
            val prompt = requireNotNull(attention).promptId
            assertTrue(PrivateScreenInput.chooseTakeOver("operation", prompt))
            assertFalse(PrivateScreenInput.submit("operation", prompt, listOf("fixture")))
        }
        assertEquals(PrivateScreenInput.Answer.TakeOver, PrivateScreenInput.await(execution, listOf(PrivateScreenInput.Field("Code"))) {})
    }

    @Test fun `a takeover started from the preview answers the pending prompt`() {
        var prompted = false
        val execution = DeviceExecution("operation") { _, _, _ -> prompted = true }
        var userControls = false
        val answer = PrivateScreenInput.await(execution, emptyList(), userTookOver = { userControls.also { userControls = true } }) {}
        assertTrue(prompted)
        assertEquals(PrivateScreenInput.Answer.TakeOver, answer)
    }

    @Test fun `a handoff prompt carries the agent's reason and the display's focused field`() {
        val field = PrivateScreenInput.Field("手机号")
        val execution = DeviceExecution("operation") { _, phase, attention ->
            assertEquals("screen.handoff", phase)
            val promptId = requireNotNull(attention).promptId
            assertEquals(PrivateScreenInput.Request(listOf(field), "抖音登录页需要你的手机号", handoff = true),
                PrivateScreenInput.request("operation", promptId))
            assertTrue(PrivateScreenInput.submit("operation", promptId, listOf("fixture-number")))
        }
        assertEquals(PrivateScreenInput.Answer.Value(listOf("fixture-number")),
            PrivateScreenInput.await(execution, listOf(field), "抖音登录页需要你的手机号", handoff = true) {})
    }

    @Test fun `a local response only reaches its matching live field request`() {
        val fixture = listOf("test account", "test credential")
        var promptId = ""
        val execution = DeviceExecution("operation") { status, phase, attention ->
            assertEquals(DeviceStatus.AWAITING_USER, status)
            assertEquals("screen.private_input", phase)
            promptId = requireNotNull(attention).promptId
            assertFalse(PrivateScreenInput.submit("another-operation", promptId, fixture))
            assertEquals(listOf("Account", "Password"), PrivateScreenInput.labels("operation", promptId))
            assertFalse(PrivateScreenInput.submit("operation", promptId, listOf("wrong count")))
            assertTrue(PrivateScreenInput.submit("operation", promptId, fixture))
            assertFalse(PrivateScreenInput.submit("operation", promptId, fixture))
        }
        assertEquals(PrivateScreenInput.Answer.Value(fixture), PrivateScreenInput.await(execution, listOf(PrivateScreenInput.Field("Account"), PrivateScreenInput.Field("Password", secret = true))) {})
        assertFalse(PrivateScreenInput.submit("operation", promptId, fixture))
    }

    @Test fun `cancelling a local prompt returns no field value`() {
        val execution = DeviceExecution("operation") { _, _, attention ->
            assertTrue(PrivateScreenInput.submit("operation", requireNotNull(attention).promptId, null))
        }
        val error = assertThrows(DeviceFailure::class.java) { PrivateScreenInput.await(execution, listOf(PrivateScreenInput.Field("Account"), PrivateScreenInput.Field("Password", secret = true))) {} }
        assertEquals(DeviceErrorCode.CANCELLED, error.error.code)
    }
}
