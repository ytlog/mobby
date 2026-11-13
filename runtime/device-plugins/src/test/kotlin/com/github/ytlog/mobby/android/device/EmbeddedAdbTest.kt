package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionException
import com.github.ytlog.mobby.android.runtime.api.device.AdbConnectionFailure
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmbeddedAdbTest {
    @Test fun `automatic connection skips a rejecting daemon and uses the next distinct local port`() =
        runBlocking {
            val attempted = mutableListOf<Int>()
            val selected = EmbeddedAdb.selectConnection(
                flowOf(37123, 37123, 37124),
            ) { port ->
                attempted += port
                if (port == 37123) throw AdbPairingRequiredException("rejected")
                "authenticated"
            }
            assertEquals(37124 to "authenticated", selected)
            assertEquals(listOf(37123, 37124), attempted)
        }

    @Test fun `an unverified endpoint cannot declare the pairing revoked`() {
        val failure = assertThrows(AdbConnectionException::class.java) {
            runBlocking {
                EmbeddedAdb.selectConnection(flowOf(37123)) {
                    throw AdbPairingRequiredException("rejected")
                }
            }
        }
        assertEquals(AdbConnectionFailure.ENDPOINT_NOT_FOUND, failure.reason)
    }

    @Test fun `endpoint line can arrive fragmented and cannot grow without a bound`() {
        val expected = "{\"uid\":2000,\"port\":32123,\"token\":\"fake-test-token\"}"
        val input = object : java.io.ByteArrayInputStream((expected + "\n").toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 7))
            override fun read(): Int = error("The startup transport must consume packets in bulk")
        }
        assertEquals(expected, EmbeddedAdb.readEndpointLine(input))
        assertThrows(IllegalStateException::class.java) { EmbeddedAdb.readEndpointLine(java.io.ByteArrayInputStream("missing newline".toByteArray())) }
        val oversized = java.io.ByteArrayInputStream(ByteArray(4096) { 'x'.code.toByte() })
        assertThrows(IllegalStateException::class.java) { EmbeddedAdb.readEndpointLine(oversized) }
        assertEquals(4096 - 1025, oversized.available())
    }

    @Test fun `ports and codes cannot add hosts commands or extra fields`() {
        assertEquals(37123, EmbeddedAdb.parsePort("37123"))
        for (value in listOf("0", "65536", "-1", "127.0.0.1:37123", "37123;id", "1\n", "", "１２３"))
            assertThrows(value, IllegalArgumentException::class.java) { EmbeddedAdb.parsePort(value) }
        EmbeddedAdb.validateCode("012345")
        for (value in listOf("12345", "1234567", "12345a", "123456\n"))
            assertThrows(IllegalArgumentException::class.java) { EmbeddedAdb.validateCode(value) }
    }

    @Test fun `bootstrap always invokes only the screen entry point with a quoted APK`() {
        val command = EmbeddedAdb.bootstrapCommand("/data/app/a'b/base.apk")
        assertEquals("shell:CLASSPATH='/data/app/a'\\''b/base.apk' /system/bin/app_process /system/bin com.github.ytlog.mobby.android.device.privileged.AdbScreenMain", command)
        assertThrows(IllegalArgumentException::class.java) { EmbeddedAdb.bootstrapCommand("/data/app/a\ncommand") }
    }
}
