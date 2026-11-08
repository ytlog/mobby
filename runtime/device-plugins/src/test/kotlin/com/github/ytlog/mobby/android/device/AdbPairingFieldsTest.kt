package com.github.ytlog.mobby.android.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPairingFieldsTest {
    @Test fun `pairing dialog preserves leading zeros and uses its own port`() {
        val fields = checkNotNull(AdbPairingFields.parse(" 012345 ", "192.0.2.10:37163"))
        assertEquals("012345", fields.code)
        assertEquals(37163, fields.port)
    }

    @Test fun `missing or malformed dialog fields cannot submit a pairing`() {
        assertNull(AdbPairingFields.parse(null, "192.0.2.10:37163"))
        assertNull(AdbPairingFields.parse("Code 012345", "192.0.2.10:37163"))
        assertNull(AdbPairingFields.parse("012345", "192.0.2.10:0"))
        assertNull(AdbPairingFields.parse("012345", "192.0.2.10:65536"))
        assertNull(AdbPairingFields.parse("012345", "37163"))
    }
}
