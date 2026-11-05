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
        data class Value(val fields: List<String>) : Answer
        data object Cancel : Answer
    }

    private class Prompt(val operationId: String, val labels: List<String>) {
        val answer = ArrayBlockingQueue<Answer>(1)
    }
    private val pending = ConcurrentHashMap<String, Prompt>()

    fun labels(operationId: String, promptId: String): List<String>? =
        pending[promptId]?.takeIf { it.operationId == operationId }?.labels

    fun submit(operationId: String, promptId: String, values: List<String>?): Boolean {
        val prompt = pending[promptId]?.takeIf { it.operationId == operationId } ?: return false
        if (values != null && (values.size != prompt.labels.size || values.all(String::isEmpty) ||
                values.any { it.length > 2000 || '\u0000' in it })) return false
        return prompt.answer.offer(if (values == null) Answer.Cancel else Answer.Value(values.toList()))
    }

    internal fun await(execution: DeviceExecution, labels: List<String>, checkActive: () -> Unit): List<String> {
        val id = UUID.randomUUID().toString()
        val prompt = Prompt(execution.operationId, labels.toList())
        pending[id] = prompt
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            execution.waiting("screen.private_input", id)
            while (System.nanoTime() < deadline) {
                checkActive()
                when (val answer = prompt.answer.poll(100, TimeUnit.MILLISECONDS)) {
                    is Answer.Value -> return answer.fields
                    Answer.Cancel -> deviceFailure(DeviceErrorCode.CANCELLED)
                    null -> Unit
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally { pending.remove(id) }
    }
}
