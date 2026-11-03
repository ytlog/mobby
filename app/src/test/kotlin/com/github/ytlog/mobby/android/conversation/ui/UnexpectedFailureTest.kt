package com.github.ytlog.mobby.android.conversation.ui

import com.github.ytlog.mobby.android.conversation.domain.gateway.*

import android.database.sqlite.SQLiteFullException
import android.os.Looper
import com.github.ytlog.mobby.android.conversation.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.security.GeneralSecurityException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnexpectedFailureTest {
    private inline fun <reified T> stub(crossinline body: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> body(method.name) } as T

    private fun vm(cancel: () -> Nothing) = ConversationViewModel(
        ConversationUseCases(
            stub { when (it) { "getState" -> MutableStateFlow(ConversationState()); else -> error(it) } },
            stub { if (it.startsWith("cancel")) cancel() else error(it) },
            stub { when (it) {
                "getStatus" -> flowOf(SystemStatus())
                "getDiagnostic" -> emptyFlow<DiagnosticOutput>()
                "agents" -> emptyList<AgentOption>()
                "nativeAccounts", "connectionSettings" -> emptyList<Any>()
                "gateways" -> emptyList<GatewayProfile>()
                "defaultGateway" -> null
                else -> error(it)
            } },
            { "id" }, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), stub { error(it) }
        )
    )

    /** The old notice told the user to check the connection for a full disk and left nothing in the log to find the cause. */
    @Test fun `a failed operation names its cause and logs where it failed without the message`() {
        val model = vm { throw SQLiteFullException("gateway sk-fixture-secret") }
        model.stop(ExecutionId("e"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UiStrings.notEnoughStorageOperationIncomplete, model.feedback.tryReceive().getOrNull())
        val logged = ShadowLog.getLogsForTag("MobbyConversation").joinToString("\n") { it.msg }
        assertTrue(logged, logged.contains("android.database.sqlite.SQLiteFullException"))
        assertTrue(logged, logged.contains("UnexpectedFailureTest"))
        assertFalse(logged, logged.contains("sk-fixture-secret"))
    }

    @Test fun `each kind of unexpected failure gets its own notice`() {
        assertEquals(UiStrings.runtimeServiceUnavailableCheckConnectionAndRetry, unexpectedFailureNotice(ConnectException("refused")))
        assertEquals(UiStrings.localDataCouldNotBeReadOrWritten, unexpectedFailureNotice(java.io.IOException("io")))
        assertEquals(UiStrings.encryptedSettingsCouldNotBeRead, unexpectedFailureNotice(IllegalStateException("wrap", GeneralSecurityException("key"))))
        val other = unexpectedFailureNotice(IllegalArgumentException("secret"))
        assertTrue(other, other.contains("IllegalArgumentException"))
        assertFalse(other, other.contains("secret"))
    }
}
