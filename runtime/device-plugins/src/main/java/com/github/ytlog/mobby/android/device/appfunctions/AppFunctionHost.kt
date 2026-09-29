package com.github.ytlog.mobby.android.device.appfunctions

import android.content.Context
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object AppFunctionHost {
    private val awaiting = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()

    /** Called only after the runtime journal has accepted and consumed the response. */
    fun respond(request: DeviceInteractionResponse): Boolean {
        if (request.interactionId != request.operationId || request.response !in setOf("confirm", "cancel")) return false
        return awaiting[request.operationId]?.offer(request.response) == true
    }

    suspend fun start(context: Context, root: File, node: String, refs: Set<String>,
                      operations: DeviceOperationPort, checkActive: () -> Unit): AppFunctionSession {
        val catalog = AppFunctionCatalog(context)
        val manager = catalog.manager() ?: deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
        val metadata = refs.associateWith { ref ->
            catalog.resolve(ref) ?: deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY, "Published function is missing or disabled")
        }
        val token = UUID.randomUUID().toString()
        val server = AppFunctionServer(context, token, metadata, operations, checkActive) { item, params ->
            manager.executeAppFunction(ExecuteAppFunctionRequest(item.packageName, item.id, AppFunctionJson.parameters(item, params)))
        }
        return try {
            AppFunctionSession(server, AppFunctionSkills.write(root, node, server.port, token, metadata))
        } catch (failure: Exception) {
            server.close()
            throw failure
        }
    }

    private fun awaitConfirmation(operationId: String, checkActive: () -> Unit): Boolean {
        val queue = ArrayBlockingQueue<String>(1)
        check(awaiting.putIfAbsent(operationId, queue) == null)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline) {
                checkActive()
                when (queue.poll(100, TimeUnit.MILLISECONDS)) {
                    "confirm" -> return true
                    "cancel" -> return false
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally { awaiting.remove(operationId, queue) }
    }

    internal class AppFunctionServer(
        private val context: Context,
        private val token: String,
        private val metadata: Map<String, AppFunctionMetadata>,
        private val operations: DeviceOperationPort,
        private val checkActive: () -> Unit,
        private val invoke: suspend (AppFunctionMetadata, JsonObject) -> ExecuteAppFunctionResponse,
    ) : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val job = Job()
        private val socketLock = Any()
        private var accepted: Socket? = null
        val port: Int = server.localPort
        private val worker = thread(name = "app-function-bridge", isDaemon = true) {
            while (alive.get()) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { continue }
                synchronized(socketLock) { if (alive.get()) accepted = socket else socket.close() }
                try {
                    socket.use { active ->
                        active.soTimeout = 250_000
                        val bytes = java.io.ByteArrayOutputStream()
                        val input = active.getInputStream()
                        while (true) {
                            val next = input.read()
                            if (next < 0 || next == 10) break
                            if (bytes.size() >= 65_536) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT, "Request exceeds 64 KiB")
                            bytes.write(next)
                        }
                        if (bytes.size() == 0 || !alive.get()) return@use
                        val line = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                        val response = runBlocking(job) { handle(line) }
                        active.getOutputStream().write((response + "\n").toByteArray(Charsets.UTF_8))
                    }
                } catch (_: Exception) {
                    // The client sees a disconnected request and must query status before retrying.
                } finally { synchronized(socketLock) { if (accepted === socket) accepted = null } }
            }
        }

        private suspend fun handle(line: String): String {
            var requestId = "invalid-request"
            var operation: DeviceOperation? = null
            var dispatched = false
            try {
                val envelope = DeviceJson.parseToJsonElement(line) as? JsonObject ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                requestId = (envelope["requestId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(DeviceOperationRules.ID) } ?: requestId
                if ((envelope["token"] as? JsonPrimitive)?.contentOrNull != token) deviceFailure(DeviceErrorCode.UNAUTHORIZED)
                val request = DeviceJson.decodeFromString<DeviceRequest>(JsonObject(envelope - "token").toString())
                if (request.protocolVersion != 1) deviceFailure(DeviceErrorCode.INCOMPATIBLE_VERSION)
                if (!request.requestId.matches(DeviceOperationRules.ID) || request.parentOperationId != null) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                if (request.plugin == "operation" && request.action == "status") {
                    if (request.args.keys != setOf("requestId")) deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                    val original = request.args["requestId"]?.jsonPrimitive?.content ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                    return DeviceJson.encodeToString(DeviceResponse.from(operations.find(original) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)))
                }
                if (request.plugin != "appfunction" || request.action != "invoke" || request.args.keys != setOf("ref", "parameters"))
                    deviceFailure(DeviceErrorCode.UNAUTHORIZED)
                val ref = request.args["ref"]?.jsonPrimitive?.content ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                val item = metadata[ref] ?: deviceFailure(DeviceErrorCode.UNAUTHORIZED)
                val params = request.args["parameters"] as? JsonObject ?: deviceFailure(DeviceErrorCode.INVALID_ARGUMENT)
                // Validate the entire argument tree before admitting an operation.
                AppFunctionJson.parameters(item, params)
                val fingerprint = MessageDigest.getInstance("SHA-256").digest("${request.plugin}\n${request.action}\n$ref\n${params}".toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val input = buildJsonObject {
                    put("app", item.packageName); put("function", item.id); put("parameters", params.toString())
                }
                val admitted = operations.admit(DeviceAdmission(request.requestId, fingerprint, "appfunction", "invoke", "basic",
                    DeviceSubject(item.id.take(1000), item.packageName.take(1000)), input))
                if (!admitted.dispatch) return DeviceJson.encodeToString(DeviceResponse.from(admitted.operation))
                operation = admitted.operation
                checkActive()
                operation = operations.update(operation.copy(revision = operation.revision + 1, status = DeviceStatus.AWAITING_USER,
                    phaseCode = "appfunction.confirm", requiresAttention = DeviceAttention("appfunction.confirm", operation.operationId,
                        listOf("confirm", "cancel"), System.currentTimeMillis() + 120_000),
                    availableActions = listOf(DeviceButton.STOP_RUN, DeviceButton.OPEN_CONVERSATION, DeviceButton.RESPOND)))
                if (!awaitConfirmation(operation.operationId, checkActive)) deviceFailure(DeviceErrorCode.CANCELLED)
                operation = operations.find(request.requestId) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
                checkActive()
                operation = operations.update(operation.copy(revision = operation.revision + 1,
                    status = DeviceStatus.RUNNING, phaseCode = "appfunction.invoke", requiresAttention = null,
                    availableActions = listOf(DeviceButton.STOP_RUN, DeviceButton.OPEN_CONVERSATION)))
                // The target can change between discovery and confirmation. Check it again at dispatch.
                val current = AppFunctionCatalog(context).resolve(ref) ?: deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY,
                    "Published function was disabled or removed")
                dispatched = true
                val response = withTimeout(120_000) { invoke(current, params) }
                checkActive()
                val result = when (response) {
                    is ExecuteAppFunctionResponse.Success -> {
                        val json = AppFunctionJson.result(current, response)
                        if (json.toString().toByteArray().size > 64_000) deviceFailure(DeviceErrorCode.RESULT_UNCONFIRMED,
                            "Target returned more than 64 KiB", EffectState.CONFIRMED)
                        DeviceResult("app_function", EffectState.CONFIRMED,
                            buildJsonObject { put("packageName", current.packageName); put("functionId", current.id); put("value", json) })
                    }
                    is ExecuteAppFunctionResponse.Error -> deviceFailure(DeviceErrorCode.UNAVAILABLE,
                        response.error.errorMessage.orEmpty().take(1000), EffectState.UNKNOWN)
                }
                operation = operations.find(request.requestId) ?: deviceFailure(DeviceErrorCode.NOT_FOUND)
                operation = operations.update(operation.copy(revision = operation.revision + 1, status = DeviceStatus.SUCCEEDED,
                    phaseCode = "succeeded", result = result, requiresAttention = null,
                    availableActions = listOf(DeviceButton.OPEN_CONVERSATION)))
                return DeviceJson.encodeToString(DeviceResponse.from(operation))
            } catch (failure: Exception) {
                val effect = if (dispatched) EffectState.UNKNOWN else EffectState.NONE
                val error = when (failure) {
                    is DeviceFailure -> failure.error
                    is SecurityException -> DeviceError(DeviceErrorCode.PERMISSION_REVOKED, effectState = effect)
                    is TimeoutCancellationException -> DeviceError(DeviceErrorCode.TIMEOUT, effectState = effect)
                    is CancellationException -> DeviceError(DeviceErrorCode.INTERRUPTED, effectState = effect)
                    is java.util.concurrent.TimeoutException -> DeviceError(DeviceErrorCode.TIMEOUT, effectState = effect)
                    is IllegalArgumentException, is kotlinx.serialization.SerializationException -> DeviceError(DeviceErrorCode.INVALID_ARGUMENT, effectState = effect)
                    else -> DeviceError(DeviceErrorCode.UNAVAILABLE, failure.message.orEmpty().take(1000), effectState = effect)
                }
                val found = operation?.let { operations.find(it.requestId) ?: it }
                if (found == null) return DeviceJson.encodeToString(DeviceResponse(requestId = requestId, accepted = false, error = error))
                val status = when {
                    error.effectState == EffectState.UNKNOWN || error.code == DeviceErrorCode.RESULT_UNCONFIRMED -> DeviceStatus.UNCONFIRMED
                    error.code == DeviceErrorCode.CANCELLED -> DeviceStatus.CANCELLED
                    else -> DeviceStatus.FAILED
                }
                return try {
                    val updated = operations.update(found.copy(revision = found.revision + 1, status = status,
                        phaseCode = status.name.lowercase(), error = error, requiresAttention = null,
                        availableActions = listOf(DeviceButton.OPEN_CONVERSATION)))
                    DeviceJson.encodeToString(DeviceResponse.from(updated))
                } catch (_: Exception) {
                    DeviceJson.encodeToString(DeviceResponse(requestId = requestId, accepted = true, operationId = found.operationId,
                        status = DeviceStatus.UNCONFIRMED, error = DeviceError(DeviceErrorCode.STORAGE_FULL, effectState = effect)))
                }
            }
        }

        fun awaitIdle(check: () -> Unit) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(245)
            while (synchronized(socketLock) { accepted != null }) {
                check()
                if (System.nanoTime() >= deadline) deviceFailure(DeviceErrorCode.TIMEOUT)
                Thread.sleep(25)
            }
        }
        override fun close() {
            alive.set(false)
            job.cancel()
            server.close()
            synchronized(socketLock) { accepted?.close() }
        }
    }

    class AppFunctionSession internal constructor(private val server: AppFunctionServer, val skills: List<File>) : AutoCloseable {
        fun awaitIdle(checkActive: () -> Unit) = server.awaitIdle(checkActive)
        override fun close() = server.close()
    }
}
