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

    @Test fun `localized labels identify values on a dialog without standard view ids`() {
        val code = "Code d’association Wi-Fi"
        val endpoint = "Adresse IP et port"
        val fields = checkNotNull(AdbPairingFields.parseLabeled(
            listOf("Associer un appareil", code, "012345", endpoint, "192.0.2.10:37163", "Annuler"),
            listOf(code), listOf(endpoint),
        ))
        assertEquals("012345", fields.code)
        assertEquals(37163, fields.port)
    }

    @Test fun `unlabelled or ambiguous numbers leave pairing to the notification`() {
        val code = "Wi-Fi pairing code"
        val endpoint = "IP address & Port"
        val address = "192.0.2.10:37163"
        assertNull(AdbPairingFields.parseLabeled(listOf("012345", address), listOf(code), listOf(endpoint)))
        assertNull(AdbPairingFields.parseLabeled(listOf(code, "012345", code, "123456", endpoint, address), listOf(code), listOf(endpoint)))
        assertNull(AdbPairingFields.parseLabeled(listOf(code, "012345", endpoint, "Waiting", address), listOf(code), listOf(endpoint)))
    }

    @Test fun `a visible labelled port can still support notification reply when the code is hidden`() {
        val endpoint = "IP address & Port"
        val dialog = listOf("Pair with device", "Wi-Fi pairing code", endpoint, "192.0.2.10:37163")
        assertNull(AdbPairingFields.parseLabeled(dialog, listOf("Wi-Fi pairing code"), listOf(endpoint)))
        assertEquals(37163, AdbPairingFields.parseLabeledPort(dialog, listOf(endpoint)))
        assertNull(AdbPairingFields.parseLabeledPort(dialog + listOf(endpoint, "192.0.2.10:37164"), listOf(endpoint)))
    }
}
