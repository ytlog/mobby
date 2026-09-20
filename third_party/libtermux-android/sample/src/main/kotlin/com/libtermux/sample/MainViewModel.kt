package com.libtermux.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libtermux.LibTermux
import com.libtermux.bootstrap.InstallState
import com.libtermux.bootstrap.NativeLibBootstrapProvider
import com.libtermux.executor.ExecutionResult
import com.libtermux.termuxConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val status: String        = "Idle",
    val isLoading: Boolean    = false,
    val installProgress: Float = 0f,
    val isReady: Boolean      = false,
    val diskUsage: String     = "",
)

sealed class UiEvent {
    data class Output(val text: String, val isError: Boolean = false) : UiEvent()
    data class Toast(val message: String)                              : UiEvent()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val termux = LibTermux.init(app, termuxConfig {
        autoInstall    = true
        logLevel       = com.libtermux.LogLevel.DEBUG
        backgroundExecutionEnabled = true
        // Fixes the EACCES/"error=13, Permission denied" crash on Android
        // 10+ (API 29+): binaries bundled by the bootstrap-arm64 module
        // (added in sample/build.gradle.kts) live under nativeLibraryDir,
        // extracted by PackageManager at install time with execute
        // permission intact — unlike the old download-to-filesDir path.
        bootstrapProvider = NativeLibBootstrapProvider.from(app)
        env("COLORTERM", "truecolor")
    })

    private val _uiState  = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // ── Install ──────────────────────────────────────────────────────────

    fun install(forceReinstall: Boolean = false) {
        viewModelScope.launch {
            termux.install(forceReinstall).collect { state ->
                when (state) {
                    is InstallState.Checking          -> updateStatus("Checking...", true)
                    is InstallState.AlreadyInstalled  -> {
                        updateStatus("Already installed", false)
                        onReady()
                    }
                    is InstallState.Downloading       -> {
                        val mb = state.bytesDownloaded / (1024 * 1024)
                        val totalMb = state.totalBytes / (1024 * 1024)
                        updateStatus("Downloading ${mb}MB / ${totalMb}MB", true, state.progress)
                    }
                    is InstallState.Extracting        -> updateStatus("Extracting...", true, state.progress)
                    is InstallState.ProcessingSymlinks-> updateStatus("Setting up symlinks...", true)
                    is InstallState.SettingPermissions-> updateStatus("Setting permissions...", true)
                    is InstallState.Verifying         -> updateStatus("Verifying...", true)
                    is InstallState.Completed         -> {
                        updateStatus("Ready ✓", false)
                        emit(UiEvent.Output("Bootstrap installed successfully!"))
                        onReady()
                    }
                    is InstallState.Failed            -> {
                        updateStatus("Error: ${state.error}", false)
                        emit(UiEvent.Toast("Installation failed: ${state.error}"))
                    }
                }
            }
        }
    }

    // ── Commands ─────────────────────────────────────────────────────────

    fun runCommand(command: String) {
        if (!termux.isInstalled) {
            emit(UiEvent.Toast("Please install bootstrap first"))
            return
        }
        viewModelScope.launch {
            emit(UiEvent.Output("$ $command"))
            val result = termux.bridge.run(command)
            if (result.stdout.isNotEmpty()) emit(UiEvent.Output(result.stdout))
            if (result.stderr.isNotEmpty()) emit(UiEvent.Output(result.stderr, true))
            emit(UiEvent.Output("[Exit: ${result.exitCode}]"))
        }
    }

    fun runPythonDemo() {
        if (!termux.isInstalled) { emit(UiEvent.Toast("Install bootstrap first")); return }
        viewModelScope.launch {
            emit(UiEvent.Output("$ python3 [demo script]"))
            val result = termux.bridge.python("""
                import sys, platform, json
                info = {
                    "python": sys.version,
                    "platform": platform.platform(),
                    "machine": platform.machine(),
                }
                import json
                print(json.dumps(info, indent=2))
            """.trimIndent())
            emit(UiEvent.Output(result.stdout, result.isFailure))
            if (result.stderr.isNotEmpty()) emit(UiEvent.Output(result.stderr, true))
        }
    }

    fun runSysInfo() {
        if (!termux.isInstalled) { emit(UiEvent.Toast("Install bootstrap first")); return }
        viewModelScope.launch {
            emit(UiEvent.Output("--- System Information ---"))
            // Use raw string to avoid escape issues with $PREFIX
            listOf("uname -a", "cat /proc/version", "free -h", "df -h ${'$'}PREFIX").forEach { cmd ->
                emit(UiEvent.Output("$ $cmd"))
                val r = termux.bridge.run(cmd)
                emit(UiEvent.Output(r.output, r.isFailure))
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun onReady() {
        _uiState.update { it.copy(isReady = true, diskUsage = termux.vfs.diskUsageString()) }
    }

    private fun updateStatus(msg: String, loading: Boolean, progress: Float = 0f) {
        _uiState.update { it.copy(status = msg, isLoading = loading, installProgress = progress) }
    }

    private fun emit(event: UiEvent) {
        viewModelScope.launch { _events.emit(event) }
    }

    override fun onCleared() {
        termux.release()
        super.onCleared()
    }
}
