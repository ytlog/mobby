package com.github.ytlog.mobby.android.conversation.data

import android.content.Context
import com.github.ytlog.mobby.android.conversation.domain.ConversationUseCases
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

object ConversationFactory {
    fun create(context: Context, client: RuntimeClient, admin: RuntimeAdminClient, diagnostics: RuntimeDiagnosticsClient, scope: CoroutineScope,
        bindDeviceSelection: ((suspend (String) -> Set<String>?, suspend (String, String) -> Unit) -> Unit)? = null): ConversationUseCases {
        val execution = RuntimeExecutionAdapter(client)
        val system = RuntimeSystemAdapter(context.applicationContext, client, admin, diagnostics)
        val repository = RoomConversationStore(ConversationDatabase.open(context), client, system, scope, execution)
        bindDeviceSelection?.invoke(repository::deviceSelection, repository::authorizeDevice)
        return ConversationUseCases(repository, execution, system, { UUID.randomUUID().toString() }, scope, ConversationPreferences(context))
    }
}
