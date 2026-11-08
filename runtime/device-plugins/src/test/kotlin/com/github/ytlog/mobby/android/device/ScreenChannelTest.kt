package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.device.privileged.ScreenServer
import com.github.ytlog.mobby.android.runtime.api.device.DeviceErrorCode
import com.github.ytlog.mobby.android.runtime.api.device.DeviceFailure
import com.github.ytlog.mobby.android.runtime.api.device.EffectState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ScreenChannelTest {
    @Test fun `ADB shell worker accepts a new client after the app disconnects`() {
        ShadowProcess.setUid(2000)
        val apk = Files.createTempFile("mobby-worker-test", ".apk")
        try {
            ScreenServer(apk.toFile()).use { worker ->
                val endpoint = JSONObject(worker.endpoint())
                ScreenChannel(endpoint, root = false).use { assertTrue(it.request("ping", emptyMap()).getBoolean("ok")) }
                ScreenChannel(endpoint, root = false).use { assertTrue(it.request("ping", emptyMap()).getBoolean("ok")) }
            }
        } finally {
            Files.delete(apk)
        }
    }

    @Test fun `authenticates once and sends the selected display action without executing shell text`() {
        withServer { endpoint, server ->
            val worker = server.submit {
                val hello = read(); assertEquals("test-ephemeral-token", hello.getString("token"))
                write(JSONObject().put("ok", true).put("uid", 2000))
                val request = read()
                assertEquals("type", request.getString("action"))
                assertEquals("汉字 ' $(id)", request.getJSONObject("args").getString("text"))
                write(JSONObject().put("ok", true).put("displayId", 7))
            }
            ScreenChannel(endpoint, root = false).use { channel ->
                assertEquals(7, channel.request("type", mapOf("text" to "汉字 ' $(id)")).getInt("displayId"))
            }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `root mode rejects a shell identity instead of accepting reduced privileges`() {
        withServer { endpoint, server ->
            val worker = server.submit { read(); write(JSONObject().put("ok", true).put("uid", 2000)) }
            assertThrows(IllegalStateException::class.java) { ScreenChannel(endpoint, root = true) }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `adb mode rejects a root identity`() {
        withServer { endpoint, server ->
            val worker = server.submit { read(); write(JSONObject().put("ok", true).put("uid", 0)) }
            assertThrows(IllegalStateException::class.java) { ScreenChannel(endpoint, root = false) }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `revoked ADB pairing blocks the next command while the worker socket is still open`() {
        withServer { endpoint, server ->
            val worker = server.submit {
                read()
                write(JSONObject().put("ok", true).put("uid", 2000))
                assertEquals("tap", read().getString("action"))
                write(JSONObject().put("ok", true).put("displayId", 7))
                assertEquals(-1, input!!.read())
            }
            var paired = true
            ScreenChannel(endpoint, root = false, verifyAdbAuthorization = {
                if (!paired) throw java.io.IOException("fixture ADB rejection")
            }).use { channel ->
                assertEquals(7, channel.request("tap", emptyMap()).getInt("displayId"))
                paired = false
                assertTrue(channel.alive)
                val denied = assertThrows(DeviceFailure::class.java) {
                    channel.request("private_input", mapOf("values" to "[]"))
                }
                assertEquals(DeviceErrorCode.ADB_AUTHORIZATION_REQUIRED, denied.error.code)
                assertFalse(channel.alive)
            }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `missing control reports an undispatched click and the same display remains usable`() {
        withServer { endpoint, server ->
            val worker = server.submit {
                read(); write(JSONObject().put("ok", true).put("uid", 2000))
                assertEquals("click", read().getString("action"))
                write(JSONObject().put("ok", false).put("error", "virtual_screen_click_failed")
                    .put("cause", "IllegalStateException").put("detail", "control_not_found"))
                assertEquals("snapshot", read().getString("action"))
                write(JSONObject().put("ok", true).put("displayId", 7))
            }
            ScreenChannel(endpoint, root = false).use { channel ->
                val denied = assertThrows(DeviceFailure::class.java) {
                    ScreenControl.request(channel, "click", mapOf("query" to "fixture"), expectedDisplay = 7)
                }
                assertEquals(DeviceErrorCode.NOT_FOUND, denied.error.code)
                assertEquals(EffectState.NONE, denied.error.effectState)
                assertTrue(denied.error.message.isNotBlank())
                assertTrue(channel.alive)
                assertEquals(7, ScreenControl.request(channel, "snapshot", expectedDisplay = 7).getInt("displayId"))
            }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `launch that was dispatched but not observed keeps an unknown effect`() {
        withServer { endpoint, server ->
            val worker = server.submit {
                read(); write(JSONObject().put("ok", true).put("uid", 2000))
                assertEquals("launch", read().getString("action"))
                write(JSONObject().put("ok", false).put("error", "virtual_screen_launch_failed")
                    .put("cause", "IllegalStateException").put("detail", "app_not_on_virtual_display"))
            }
            ScreenChannel(endpoint, root = false).use { channel ->
                val failure = assertThrows(DeviceFailure::class.java) {
                    ScreenControl.request(channel, "launch", mapOf("package" to "com.example.app"), expectedDisplay = 7)
                }
                assertEquals(DeviceErrorCode.RESULT_UNCONFIRMED, failure.error.code)
                assertEquals(EffectState.UNKNOWN, failure.error.effectState)
            }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `broken transport closes and never retries an input`() {
        withServer { endpoint, server ->
            val worker = server.submit {
                read(); write(JSONObject().put("ok", true).put("uid", 2000))
                assertEquals("tap", read().getString("action"))
                output!!.writeInt(8 * 1024 * 1024); output!!.flush()
            }
            ScreenChannel(endpoint, root = false).use { channel ->
                assertThrows(IllegalArgumentException::class.java) { channel.request("tap", mapOf("x" to "0.5", "y" to "0.5")) }
                assertFalse(channel.alive)
                assertThrows(IllegalStateException::class.java) { channel.request("tap", emptyMap()) }
            }
            worker.get(2, TimeUnit.SECONDS)
        }
    }

    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private fun read(): JSONObject {
        val stream = input!!; val size = stream.readInt(); val bytes = ByteArray(size); stream.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun write(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        output!!.writeInt(bytes.size); output!!.write(bytes); output!!.flush()
    }
    private fun withServer(block: (JSONObject, java.util.concurrent.ExecutorService) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val server = Executors.newSingleThreadExecutor()
            val accepts = Executors.newSingleThreadExecutor()
            val accepted = accepts.submit<java.net.Socket> {
                listener.accept().also { input = DataInputStream(it.getInputStream()); output = DataOutputStream(it.getOutputStream()) }
            }
            // Server work waits for accept, while the client connects on this thread.
            val serial = object : java.util.concurrent.AbstractExecutorService() {
                override fun execute(command: Runnable) { server.execute { accepted.get(2, TimeUnit.SECONDS); command.run() } }
                override fun shutdown() = server.shutdown()
                override fun shutdownNow() = server.shutdownNow()
                override fun isShutdown() = server.isShutdown
                override fun isTerminated() = server.isTerminated
                override fun awaitTermination(timeout: Long, unit: TimeUnit) = server.awaitTermination(timeout, unit)
            }
            try { block(JSONObject().put("port", listener.localPort).put("token", "test-ephemeral-token"), serial) }
            finally { if (accepted.isDone) accepted.get().close(); server.shutdownNow(); accepts.shutdownNow() }
        }
    }
}
