package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Values travel from the local form to the waiting worker in memory. */
object PrivateScreenInput {
    private sealed interface Answer {
        data class Value(val text: String) : Answer
        data object Cancel : Answer
    }

    private class Prompt(val operationId: String) {
        val answer = ArrayBlockingQueue<Answer>(1)
    }
    private val pending = ConcurrentHashMap<String, Prompt>()

    fun submit(operationId: String, promptId: String, value: String?): Boolean {
        if (value != null && (value.isEmpty() || value.length > 2000 || '\u0000' in value)) return false
        val prompt = pending[promptId]?.takeIf { it.operationId == operationId } ?: return false
        return prompt.answer.offer(if (value == null) Answer.Cancel else Answer.Value(value))
    }

    internal fun await(execution: DeviceExecution, checkActive: () -> Unit): String {
        val id = UUID.randomUUID().toString()
        val prompt = Prompt(execution.operationId)
        pending[id] = prompt
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            execution.waiting("screen.private_input", id)
            while (System.nanoTime() < deadline) {
                checkActive()
                when (val answer = prompt.answer.poll(100, TimeUnit.MILLISECONDS)) {
                    is Answer.Value -> return answer.text
                    Answer.Cancel -> deviceFailure(DeviceErrorCode.CANCELLED)
                    null -> Unit
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally { pending.remove(id) }
    }
}
