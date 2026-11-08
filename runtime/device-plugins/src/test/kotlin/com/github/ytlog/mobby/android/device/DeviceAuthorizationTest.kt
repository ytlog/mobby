package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceAuthorizationTest {
    @Test fun selectedScreenReusesSystemConnectionWithoutAskingAgain() = runBlocking {
        val screen = DeviceCatalog.actionRef("screen", "snapshot")
        var connected = false
        var reconnects = 0
        val authorization = DeviceAuthorization({ setOf(screen) }, { connected }, { error("Already selected") }, {},
            restoreSelected = {
                assertEquals(screen, it)
                reconnects++
                connected = true
            })
        val operation = DeviceExecution("op") { _, _, _ -> error("A selected screen should reconnect before showing a prompt") }
        authorization.require(screen, operation)
        authorization.require(screen, operation)
        assertEquals(1, reconnects)
    }

    @Test fun unselectedScreenStillNeedsConversationApproval() = runBlocking {
        val screen = DeviceCatalog.actionRef("screen", "snapshot")
        val chips = mutableSetOf<String>()
        val authorization = DeviceAuthorization({ chips.toSet() }, { true }, { chips += it }, {},
            restoreSelected = { error("No reconnect before the user grants this conversation access") })
        val operation = DeviceExecution("op") { _, _, attention ->
            assertTrue(DeviceAuthorization.respond(DeviceOperationResponse("response", "run", "op",
                requireNotNull(attention).promptId, 2, "confirm")))
        }
        authorization.require(screen, operation)
        assertEquals(setOf(screen), chips)
    }

    @Test fun removingTheScreenChipDuringReconnectStillBlocksTheAction() = runBlocking {
        val screen = DeviceCatalog.actionRef("screen", "snapshot")
        val chips = mutableSetOf(screen)
        var connected = false
        val authorization = DeviceAuthorization({ chips.toSet() }, { connected }, { error("No approval granted") }, {},
            restoreSelected = { chips.clear(); connected = true })
        val operation = DeviceExecution("op") { _, _, attention ->
            assertTrue(DeviceAuthorization.respond(DeviceOperationResponse("response", "run", "op",
                requireNotNull(attention).promptId, 2, "cancel")))
        }
        val denied = runCatching { authorization.require(screen, operation) }.exceptionOrNull() as DeviceFailure
        assertEquals(DeviceErrorCode.UNAUTHORIZED, denied.error.code)
    }

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
