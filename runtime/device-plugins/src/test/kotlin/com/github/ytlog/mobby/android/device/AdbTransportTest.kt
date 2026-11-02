package com.github.ytlog.mobby.android.device

import io.github.muntashirakon.adb.AdbConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdbTransportTest {
    @Test fun `a legacy unencrypted daemon cannot establish the embedded connection`() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = object : Certificate("test") {
            override fun getEncoded() = byteArrayOf()
            override fun verify(key: PublicKey?) = Unit
            override fun verify(key: PublicKey?, provider: String?) = Unit
            override fun getPublicKey() = key.public
            override fun toString() = "fake test certificate"
        }
        ServerSocket(0).use { server ->
            val peer = Thread {
                server.accept().use { socket ->
                    // Wire-format CNXN without STLS: a legacy peer must be rejected.
                    val command = 0x4e584e43
                    val frame = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(command).putInt(0x01000001).putInt(1024).putInt(0).putInt(0).putInt(command.inv()).array()
                    socket.getOutputStream().write(frame)
                    socket.getOutputStream().flush()
                    socket.soTimeout = 3000
                    runCatching { while (socket.getInputStream().read(ByteArray(1024)) >= 0) Unit }
                }
            }.apply { start() }
            AdbConnection.Builder("127.0.0.1", server.localPort).setApi(33)
                .setPrivateKey(key.private).setCertificate(certificate).build().use { connection ->
                    assertThrows(IOException::class.java) { connection.connect(2, TimeUnit.SECONDS, true) }
                    assertFalse(connection.isConnectionEstablished)
                }
            peer.join(4000)
            assertFalse("Test daemon must be closed", peer.isAlive)
        }
    }
}
