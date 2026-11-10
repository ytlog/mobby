package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.deviceFailure
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Screen prompts are answered locally. Field values only travel in memory. */
object PrivateScreenInput {
    internal sealed interface Answer {
        data class Value(val fields: List<String>) : Answer
        data object TakeOver : Answer
        data object Cancel : Answer
    }

    /** One field on the display; [secret] hides what the user types, as the display does for a password field. */
    data class Field(val label: String, val secret: Boolean = false)
    /** What the prompt shows: the fields it fills (none for a takeover-only handoff) and the agent's reason. */
    data class Request(val fields: List<Field>, val reason: String, val handoff: Boolean)

    private class Prompt(val operationId: String, val request: Request) {
        val labels get() = request.fields.map(Field::label)
        val answer = ArrayBlockingQueue<Answer>(1)
        private val answered = AtomicBoolean()
        fun respond(value: Answer): Boolean = answered.compareAndSet(false, true) && answer.offer(value)
    }
    private val pending = ConcurrentHashMap<String, Prompt>()

    fun request(operationId: String, promptId: String): Request? =
        pending[promptId]?.takeIf { it.operationId == operationId }?.request

    fun labels(operationId: String, promptId: String): List<String>? = request(operationId, promptId)?.fields?.map(Field::label)

    fun submit(operationId: String, promptId: String, values: List<String>?): Boolean {
        val prompt = pending[promptId]?.takeIf { it.operationId == operationId } ?: return false
        if (values != null && (values.size != prompt.labels.size || values.all(String::isEmpty) ||
                values.any { it.length > 2000 || '\u0000' in it })) return false
        return prompt.respond(if (values == null) Answer.Cancel else Answer.Value(values.toList()))
    }

    fun chooseTakeOver(operationId: String, promptId: String): Boolean =
        pending[promptId]?.takeIf { it.operationId == operationId }?.respond(Answer.TakeOver) == true

    /** [userTookOver] reports a takeover the user started from the preview; it answers the prompt like the dialog's takeover. */
    internal fun await(execution: DeviceExecution, fields: List<Field>, reason: String = "", handoff: Boolean = fields.isEmpty(),
        userTookOver: () -> Boolean = { false }, checkActive: () -> Unit): Answer {
        require(handoff || fields.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val prompt = Prompt(execution.operationId, Request(fields.toList(), reason, handoff))
        pending[id] = prompt
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            execution.waiting(if (handoff) "screen.handoff" else "screen.private_input", id)
            while (System.nanoTime() < deadline) {
                checkActive()
                if (userTookOver()) prompt.respond(Answer.TakeOver)
                when (val answer = prompt.answer.poll(100, TimeUnit.MILLISECONDS)) {
                    Answer.Cancel -> deviceFailure(DeviceErrorCode.CANCELLED)
                    null -> Unit
                    else -> return answer
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally { pending.remove(id) }
    }
}
