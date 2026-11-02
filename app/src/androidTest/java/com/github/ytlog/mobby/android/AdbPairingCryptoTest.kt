package com.github.ytlog.mobby.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbPairingCryptoTest {
    @Test fun nativePairingDerivesMatchingKeysAndRejectsDifferentSecrets() {
        for (same in listOf(true, false)) {
            val client = "adb pair client\u0000".toByteArray()
            val server = "adb pair server\u0000".toByteArray()
            val alice = Spake2Context(Spake2Role.Alice, client, server)
            val bob = Spake2Context(Spake2Role.Bob, server, client)
            try {
                val a = alice.generateMessage("fake-test-pairing-secret".toByteArray())
                val b = bob.generateMessage((if (same) "fake-test-pairing-secret" else "different-test-secret").toByteArray())
                assertEquals(same, alice.processMessage(b).contentEquals(bob.processMessage(a)))
            } finally { alice.destroy(); bob.destroy(); alice.destroy(); bob.destroy() }
        }
    }
}
