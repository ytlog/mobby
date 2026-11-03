package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.api.gateway.GatewayModelSummary
import com.github.ytlog.mobby.android.runtime.engine.launchMode
import com.libtermux.executor.OutputLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.io.File

internal class NativeAgentAdmin(private val context: Context, private val runtime: RuntimeEnvironment,
    private val state: StateFlow<EnvironmentSnapshot>, val store: NativeAuthStore = NativeAuthStore(context)) {
    private val loginGate = Mutex()
    private val processes = ProcessRegistry(context, namespace = "native-agent-login-process")
    private fun ready(agent: AgentId) = state.value.phase == EnvironmentPhase.READY &&
        (agent != AgentId.CLAUDE_CODE || runtime.claudeReady) && (agent != AgentId.OPEN_CODE || runtime.opencodeReady)
    private suspend fun <T> result(block: suspend () -> T): AdminResult<T> = try { AdminResult.Success(block()) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    suspend fun accounts() = withContext(Dispatchers.IO) { result { store.accounts() } }
    suspend fun settings() = withContext(Dispatchers.IO) { result { store.settings() } }
    suspend fun save(setting: AgentConnectionSetting) = withContext(Dispatchers.IO) { result { store.save(setting) } }
    suspend fun logout(agent: AgentId) = withContext(Dispatchers.IO) { result { store.logout(agent) } }
    suspend fun methods(agent: AgentId): AdminResult<List<NativeLoginMethod>> = withContext(Dispatchers.IO) {
        if (!ready(agent)) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.NOT_READY, true))
        if (!loginGate.tryLock()) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true))
        try { result {
            val values = mutableListOf<NativeLoginMethod>()
            store.open(agent, runtime.sdk.vfs.homeDir, login = true).use { lease ->
                run("methods", agent, "", lease, {}, emptyFlow()) { message ->
                    if (message["type"]?.jsonPrimitive?.content == "methods") message.getValue("methods").jsonArray.forEach {
                        values += NativeLoginMethod(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("label").jsonPrimitive.content)
                    }
                }
            }
            values
        } } finally { loginGate.unlock() }
    }
    suspend fun login(agent: AgentId, method: String, updates: (NativeLoginUpdate) -> Unit, input: Flow<String>): AdminResult<Unit> = withContext(Dispatchers.IO) {
        if (!ready(agent)) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.NOT_READY, true))
        if (!loginGate.tryLock()) return@withContext AdminResult.Failed(RuntimeError(ErrorCode.BUSY, true))
        try {
            withTimeout(300_000) { result {
                store.open(agent, runtime.sdk.vfs.homeDir, login = true).use { lease ->
                    var account: JsonObject? = null
                    run("login", agent, method, lease, updates, input) { message ->
                        if (message["type"]?.jsonPrimitive?.content == "account") account = message
                    }
                    val value = requireNotNull(account)
                    check(lease.credential.isFile && lease.credential.length() <= 262144)
                    val models = value.getValue("models").jsonArray.map {
                        GatewayModelSummary(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("name").jsonPrimitive.content)
                    }
                    currentCoroutineContext().ensureActive()
                    store.saveLogin(agent, value.getValue("provider").jsonPrimitive.content,
                        value.getValue("label").jsonPrimitive.content, models, lease.credential.readText(), lease.supplemental())
                }
            } }
        } catch (_: TimeoutCancellationException) { AdminResult.Failed(RuntimeError(ErrorCode.TIMEOUT, true)) }
        finally { loginGate.unlock() }
    }
    private suspend fun run(operation: String, agent: AgentId, method: String, lease: NativeAuthStore.Lease,
        updates: (NativeLoginUpdate) -> Unit, input: Flow<String>, consume: (JsonObject) -> Unit) {
        var exit: Int? = null
        runtime.sdk.executor.executeArgsStreaming(listOf(File(runtime.sdk.vfs.binDir, "node").absolutePath,
            File(context.filesDir, "native-auth.cjs").absolutePath, operation, agent.launchMode().name, method, runtime.executable(agent.launchMode())),
            runtime.workspace, lease.environment, onStarted = processes::started, onTerminated = processes::terminated, input = input.map { (it + "\n").toByteArray() }, timeoutMs = 300_000).collect { line ->
            when (line) {
                is OutputLine.Exit -> exit = line.code
                is OutputLine.Stderr -> Unit // Never store raw authentication diagnostics.
                is OutputLine.Stdout -> {
                    require(line.text.length <= 512 * 1024)
                    val message = Json.parseToJsonElement(line.text).jsonObject
                    when (message["type"]?.jsonPrimitive?.content) {
                        "failed" -> error("Native authentication failed")
                        "update" -> {
                            val url = message["url"]?.jsonPrimitive?.contentOrNull?.also {
                                val uri = java.net.URI(it)
                                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
                            }
                            updates(NativeLoginUpdate(url, message["code"]?.jsonPrimitive?.contentOrNull,
                                message["prompt"]?.jsonPrimitive?.contentOrNull, message["options"]?.jsonArray?.map {
                                    NativeLoginMethod(it.jsonObject.getValue("id").jsonPrimitive.content, it.jsonObject.getValue("label").jsonPrimitive.content)
                                }.orEmpty(), message["instructions"]?.jsonPrimitive?.contentOrNull))
                        }
                        else -> consume(message)
                    }
                }
            }
        }
        check(exit == 0) { "Native authentication did not complete" }
    }
}
