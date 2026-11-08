package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class DeviceAuthorization(
    private val selected: suspend () -> Set<String>,
    private val granted: (String) -> Boolean,
    private val remember: suspend (String) -> Unit,
    private val checkActive: () -> Unit,
    private val restoreSelected: suspend (String) -> Unit = {},
) {
    suspend fun permits(ref: String) = ref in selected() && granted(ref)

    suspend fun require(ref: String, execution: DeviceExecution) {
        if (ref in selected()) {
            if (granted(ref)) return
            checkActive()
            restoreSelected(ref)
            checkActive()
            if (permits(ref)) return
        }
        val responses = ArrayBlockingQueue<String>(1)
        val promptId = java.util.UUID.randomUUID().toString()
        pending[promptId] = Pending(execution.operationId, responses)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            execution.authorizing(promptId, System.currentTimeMillis() + 120_000)
            while (System.nanoTime() < deadline) {
                checkActive()
                when (responses.poll(100, TimeUnit.MILLISECONDS)) {
                    "cancel" -> deviceFailure(DeviceErrorCode.UNAUTHORIZED)
                    "confirm" -> {
                        checkActive()
                        if (!granted(ref)) deviceFailure(DeviceErrorCode.PERMISSION_REVOKED)
                        if (ref !in selected()) remember(ref)
                        if (!permits(ref)) deviceFailure(DeviceErrorCode.PERMISSION_REVOKED)
                        return
                    }
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally { pending.remove(promptId) }
    }

    private data class Pending(val operation: String, val responses: ArrayBlockingQueue<String>)

    companion object {
        private val pending = ConcurrentHashMap<String, Pending>()
        fun respond(request: DeviceOperationResponse): Boolean {
            val waiting = pending[request.promptId] ?: return false
            return request.operationId == waiting.operation && request.response in setOf("confirm", "cancel") &&
                waiting.responses.offer(request.response)
        }
    }
}
