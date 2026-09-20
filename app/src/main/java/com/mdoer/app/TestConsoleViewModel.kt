package com.mdoer.app

import android.app.Application
import android.content.*
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConsoleUiState(val input: String = "", val mode: AgentMode = AgentMode.SHELL, val runtime: RuntimeState = RuntimeState())

class TestConsoleViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ConsoleUiState())
    val state = mutableState.asStateFlow()
    private var service: RuntimeService? = null
    private var observation: Job? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RuntimeService.LocalBinder).service
            observation?.cancel()
            observation = viewModelScope.launch { service!!.state.collect { runtime -> mutableState.update { it.copy(runtime = runtime) } } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            mutableState.update { it.copy(runtime = it.runtime.copy(ready = false, busy = false, status = "服务已断开")) }
        }
    }
    private val bound = application.bindService(Intent(application, RuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    fun input(value: String) { mutableState.update { it.copy(input = value) } }
    fun mode(value: AgentMode) { if (!state.value.runtime.busy) mutableState.update { it.copy(mode = value) } }
    fun send() { val current = state.value; if (service?.submit(current.mode, current.input) == true) input("") }
    fun stop() { service?.stop() }
    fun clear() { service?.clear() }
    fun retry() { service?.initialize() }
    override fun onCleared() {
        observation?.cancel()
        if (bound) getApplication<Application>().unbindService(connection)
        service = null
    }
}
