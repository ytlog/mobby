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
        assertEquals(PrivateScreenInput.Answer.TakeOver, PrivateScreenInput.await(execution, listOf("Code")) {})
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
        assertEquals(PrivateScreenInput.Answer.Value(fixture), PrivateScreenInput.await(execution, listOf("Account", "Password")) {})
        assertFalse(PrivateScreenInput.submit("operation", promptId, fixture))
    }

    @Test fun `cancelling a local prompt returns no field value`() {
        val execution = DeviceExecution("operation") { _, _, attention ->
            assertTrue(PrivateScreenInput.submit("operation", requireNotNull(attention).promptId, null))
        }
        val error = assertThrows(DeviceFailure::class.java) { PrivateScreenInput.await(execution, listOf("Account", "Password")) {} }
        assertEquals(DeviceErrorCode.CANCELLED, error.error.code)
    }
}
