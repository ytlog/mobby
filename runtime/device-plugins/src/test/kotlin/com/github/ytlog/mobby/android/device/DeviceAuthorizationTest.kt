package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceAuthorizationTest {
    @Test fun removingAChipRequiresNewApprovalAndReadDoesNotAuthorizeWrite() = runBlocking {
        val read = DeviceCatalog.actionRef("contacts", "list")
        val write = DeviceCatalog.actionRef("contacts", "delete")
        val chips = mutableSetOf<String>()
        val prompts = mutableListOf<String>()
        var response = "confirm"
        val authorization = DeviceAuthorization({ chips.toSet() }, { true }, { chips += it }, {})
        val operation = DeviceExecution("op") { status, _, attention ->
            assertEquals(DeviceStatus.AWAITING_USER, status)
            val prompt = requireNotNull(attention)
            prompts += prompt.promptId
            assertFalse(DeviceAuthorization.respond(DeviceOperationResponse("bad", "run", "other-op", prompt.promptId, 2, response)))
            assertTrue(DeviceAuthorization.respond(DeviceOperationResponse("response", "run", "op", prompt.promptId, 2, response)))
        }
        authorization.require(read, operation)
        assertEquals(setOf(read), chips)
        authorization.require(read, operation)
        assertEquals(1, prompts.size)
        assertFalse(authorization.permits(write))

        chips.remove(read)
        response = "cancel"
        val denied = runCatching { authorization.require(read, operation) }.exceptionOrNull() as DeviceFailure
        assertEquals(DeviceErrorCode.UNAUTHORIZED, denied.error.code)
        assertEquals(2, prompts.size)
        assertNotEquals(prompts[0], prompts[1])
        assertTrue(chips.isEmpty())
        assertFalse(DeviceAuthorization.respond(DeviceOperationResponse("late", "run", "op", prompts.last(), 2, "confirm")))
    }

    @Test fun approvalWithoutSystemPermissionDoesNotWriteTheChip() = runBlocking {
        var stored = false
        val authorization = DeviceAuthorization({ emptySet() }, { false }, { stored = true }, {})
        val operation = DeviceExecution("op") { _, _, attention ->
            assertTrue(DeviceAuthorization.respond(DeviceOperationResponse("response", "run", "op",
                requireNotNull(attention).promptId, 2, "confirm")))
        }
        val denied = runCatching { authorization.require("plugin:device:screen", operation) }.exceptionOrNull() as DeviceFailure
        assertEquals(DeviceErrorCode.PERMISSION_REVOKED, denied.error.code)
        assertFalse(stored)
    }
}
