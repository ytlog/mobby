/**
 * LibTermux-Android
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Author: cybernahid-dev (Systems Developer)
 * Project: https://github.com/AeonCoreX-Lab/libtermux-android
 */
package com.libtermux.executor

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    var isAlive: Boolean = true,
)

/**
 * Manages multiple named terminal sessions.
 * Each session has its own command context and I/O stream collection.
 */
class SessionManager(
    private val executor: CommandExecutor,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, SessionHandle>()

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    /** Create and start a new interactive session */
    suspend fun createSession(name: String = "session-${sessions.size + 1}"): SessionHandle {
        val session = Session(name = name)
        val handle  = SessionHandle(session, scope, executor, _sessionEvents)
        sessions[session.id] = handle
        handle.start()
        _sessionEvents.emit(SessionEvent.Created(session))
        return handle
    }

    fun getSession(id: String): SessionHandle? = sessions[id]
    fun getAllSessions(): List<SessionHandle> = sessions.values.toList()

    fun closeSession(id: String) {
        sessions.remove(id)?.close()
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    val activeCount: Int get() = sessions.count { it.value.session.isAlive }
}

class SessionHandle(
    val session: Session,
    private val scope: CoroutineScope,
    private val executor: CommandExecutor,
    private val events: MutableSharedFlow<SessionEvent>,
) {
    private val _output = MutableSharedFlow<OutputLine>(replay = 200, extraBufferCapacity = 200)
    val output: SharedFlow<OutputLine> = _output.asSharedFlow()

    private var job: Job? = null

    internal fun start() {
        session.isAlive = true
    }

    /** Run a command in this session and collect output */
    fun run(command: String): Job = scope.launch {
        executor.executeStreaming(command).collect { line ->
            _output.emit(line)
            if (line is OutputLine.Exit) {
                events.emit(SessionEvent.CommandFinished(session, line.code))
            }
        }
    }

    fun close() {
        job?.cancel()
        session.isAlive = false
        scope.launch { events.emit(SessionEvent.Closed(session)) }
    }
}

sealed class SessionEvent {
    data class Created(val session: Session)                       : SessionEvent()
    data class CommandFinished(val session: Session, val exit: Int): SessionEvent()
    data class Closed(val session: Session)                        : SessionEvent()
}
