package com.github.ytlog.mobby.android.device

import android.content.Context
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdbAuthorizationCheckTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun pair() = context.getSharedPreferences("screen-control", Context.MODE_PRIVATE).edit().putBoolean("adb-paired", true).commit()

    @Before fun paired() { assertTrue(pair()) }

    @Test fun `an unreachable daemon keeps the pairing for a later reconnect`() {
        for (failure in listOf(java.io.IOException("fixture port closed"), java.net.SocketTimeoutException("fixture timeout"),
            IllegalStateException("adb_authorization_timeout"))) {
            assertSame(failure, assertThrows(Exception::class.java) { EmbeddedAdb.checkAuthorization(context) { throw failure } })
            assertTrue(EmbeddedAdb.paired(context))
        }
    }

    @Test fun `a rejected identity or a different daemon revokes the pairing`() {
        for (failure in listOf(AdbAuthenticationFailedException(), AdbPairingRequiredException("fixture"), AdbDaemonChangedException())) {
            assertTrue(pair())
            assertThrows(AdbAuthorizationRevoked::class.java) { EmbeddedAdb.checkAuthorization(context) { throw failure } }
            assertFalse(EmbeddedAdb.paired(context))
        }
    }

    @Test fun `a verified daemon leaves the pairing untouched`() {
        EmbeddedAdb.checkAuthorization(context) { }
        assertTrue(EmbeddedAdb.paired(context))
    }
}
