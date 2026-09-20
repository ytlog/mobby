package com.mobby.app

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OutputItem(val id: Long, val text: String, val error: Boolean = false)
data class RuntimeState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val dependenciesReady: Boolean = false,
    val initializing: Boolean = true,
    val status: String = "初始化中",
    val output: List<OutputItem> = emptyList()
)

class RuntimeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@RuntimeService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()
    private lateinit var runtime: RuntimeEnvironment
    private var job: Job? = null
    private var sequence = 0L
    private val prefs by lazy { getSharedPreferences("runtime", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeEnvironment(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("runtime", "任务运行", NotificationManager.IMPORTANCE_LOW)
        )
        if (prefs.getBoolean("running", false)) {
            append("上次任务因 App 进程结束而中断，未自动重发。", true)
            prefs.edit().putBoolean("running", false).commit()
        }
        initialize()
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun initialize() {
        if (job?.isActive == true) return
        mutableState.update { it.copy(initializing = true, ready = false, status = "初始化中") }
        job = scope.launch {
            try {
                withTimeout(120_000) { runtime.initialize { message -> scope.launch { append(message) } } }
                mutableState.update { it.copy(ready = true, dependenciesReady = runtime.dependenciesReady, status = if (runtime.dependenciesReady) "就绪" else "部分依赖异常") }
            } catch (e: TimeoutCancellationException) {
                append("初始化超过 2 分钟，请检查运行环境后重试。", true)
                mutableState.update { it.copy(status = "初始化失败") }
            } catch (e: LinkageError) {
                append("原生运行组件加载失败：${e.message}", true)
                mutableState.update { it.copy(status = "初始化失败") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                append(e.message ?: e.javaClass.simpleName, true)
                mutableState.update { it.copy(status = "初始化失败") }
            } finally { mutableState.update { it.copy(initializing = false) } }
        }
    }

    fun submit(mode: AgentMode, input: String): Boolean {
        if (!state.value.ready || state.value.busy || input.isBlank()) return false
        if (input.toByteArray().size > 65536) { append("输入超过 64 KiB，请缩短内容。", true); return false }
        try {
            // Validate installation before accepting the input.
            runtime.executable(mode)
            runtime.validateGateway(mode)
            startService(Intent(this, RuntimeService::class.java))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            startForeground(1, Notification.Builder(this, "runtime")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("mobby 正在执行任务").setContentText(mode.label)
                .setContentIntent(open).setOngoing(true).build())
        } catch (e: Exception) { append(e.message ?: "启动任务失败", true); stopSelf(); return false }
        mutableState.update { it.copy(busy = true, status = "执行中") }
        prefs.edit().putBoolean("running", true).commit()
        append("开始 ${mode.label} 任务")
        job = scope.launch {
            var agentFailed = false
            try {
                runtime.run(mode, input).collect { line ->
                    when (line) {
                        is com.libtermux.executor.OutputLine.Stdout -> {
                            val event = AgentAdapter.parse(mode, line.text)
                            agentFailed = agentFailed || event.error
                            append(event.text, event.error)
                        }
                        is com.libtermux.executor.OutputLine.Stderr -> append(line.text, true)
                        is com.libtermux.executor.OutputLine.Exit -> {
                            val success = line.code == 0 && !agentFailed
                            append("退出码：${line.code}", !success)
                            mutableState.update { it.copy(status = if (success) "完成" else "失败") }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                append("任务超过 10 分钟，已请求终止进程组。", true)
                mutableState.update { it.copy(status = "超时") }
            } catch (e: CancellationException) {
                append("任务已停止。")
                mutableState.update { it.copy(status = "已停止") }
            } catch (e: Exception) {
                append(e.message ?: e.javaClass.simpleName, true)
                mutableState.update { it.copy(status = "失败") }
            } finally {
                prefs.edit().putBoolean("running", false).commit()
                mutableState.update { it.copy(busy = false) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return true
    }
    fun stop() { if (state.value.busy) { mutableState.update { it.copy(status = "停止中") }; job?.cancel() } }
    fun clear() { mutableState.update { it.copy(output = emptyList()) } }
    private fun append(text: String, error: Boolean = false) {
        val item = OutputItem(++sequence, text.take(16384), error)
        mutableState.update { previous ->
            var items = (previous.output + item).takeLast(2000)
            // Bound both rows and retained characters, including JSON diagnostic events.
            var size = items.sumOf { it.text.length }
            var remove = 0
            while (size > 1_000_000 && remove < items.lastIndex) { size -= items[remove++].text.length }
            items = items.drop(remove)
            previous.copy(output = items)
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        append("系统要求结束后台任务。", true)
        stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
