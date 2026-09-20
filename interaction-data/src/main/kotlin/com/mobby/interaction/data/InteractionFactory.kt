package com.mobby.interaction.data

import android.content.Context
import com.mobby.interaction.domain.InteractionUseCases
import com.mobby.runtime.api.*
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

object InteractionFactory {
    fun create(context: Context, client: RuntimeClient, admin: RuntimeAdminClient, diagnostics: RuntimeDiagnosticsClient, scope: CoroutineScope): InteractionUseCases {
        val execution = RuntimeExecutionAdapter(client)
        val system = RuntimeSystemAdapter(context.applicationContext, client, admin, diagnostics)
        val repository = RoomInteractionRepository(InteractionDatabase.open(context), client, system, scope, execution)
        return InteractionUseCases(repository, execution, system, { UUID.randomUUID().toString() }, scope)
    }
}
