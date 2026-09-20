package com.libtermux.os.gui.vnc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.libtermux.utils.TermuxLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Pure Kotlin RFB 3.8 (VNC) client.
 *
 * Connects to a VNC server on localhost, negotiates the protocol,
 * and continuously reads FramebufferUpdate messages — rendering each
 * rectangle into an [ImageBitmap] that the Compose [DistroDisplay]
 * collects and draws.
 *
 * Supported encodings:
 *   - Raw (type=0)   — required baseline
 *   - CopyRect (1)   — scroll / copy optimization
 *
 * Pixel format requested: 32bpp BGRA little-endian → maps directly
 * to Android Bitmap.Config.ARGB_8888 for zero-copy rendering.
 */
class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int    = 5901,
    private val password: String = "",
) {
    // ── Public state ──────────────────────────────────────────────────────

    private val _state      = MutableStateFlow<VncState>(VncState.Disconnected)
    private val _framebuffer= MutableStateFlow<ImageBitmap?>(null)

    val state:       StateFlow<VncState>      = _state.asStateFlow()
    val framebuffer: StateFlow<ImageBitmap?> = _framebuffer.asStateFlow()

    var serverWidth:  Int = 0  ; private set
    var serverHeight: Int = 0  ; private set
    var serverName:   String = ""; private set

    // ── Internals ─────────────────────────────────────────────────────────

    private var socket: Socket?           = null
    private var input:  DataInputStream?  = null
    private var output: DataOutputStream? = null
    private var bitmap: Bitmap?           = null
    private var readerJob: Job?           = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Connection ────────────────────────────────────────────────────────

    /** Connect, handshake, and start the read loop. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        _state.value = VncState.Connecting
        try {
            val sock = Socket(host, port).also {
                it.tcpNoDelay     = true
                it.soTimeout      = 30_000
                socket = it
            }
            input  = DataInputStream(sock.getInputStream().buffered())
            output = DataOutputStream(sock.getOutputStream().buffered())

            _state.value = VncState.Handshaking
            handshake()

            val (w, h, name) = serverInit()
            serverWidth  = w
            serverHeight = h
            serverName   = name

            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            _state.value = VncState.Connected(w, h, name)
            TermuxLogger.i("VNC connected: $name ${w}x${h} on $host:$port")

            // Set preferred pixel format (32bpp BGRA LE)
            sendSetPixelFormat()
            // Declare supported encodings
            sendSetEncodings()
            // Request first full update
            sendFbUpdateRequest(incremental = false)

            // Start async read loop
            readerJob = scope.launch { readLoop() }

        } catch (e: Exception) {
            TermuxLogger.e("VNC connect failed", e)
            _state.value = VncState.Failed(e.message ?: "Connection failed", e)
            closeSocket()
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        scope.cancel()
        closeSocket()
        _state.value = VncState.Disconnected
    }

    // ── Input events ──────────────────────────────────────────────────────

    /** Send a key press or release. [keySym] is an X11 keysym. */
    fun sendKey(keySym: Int, down: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(4)              // type: KeyEvent
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)             // padding
            out.writeInt(keySym)
            out.flush()
        }
    }

    /**
     * Send a mouse event.
     * [x], [y] — desktop coordinates (NOT screen coordinates).
     * [buttons] — bitmask from [MouseButton].
     */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(5)              // type: PointerEvent
            out.writeByte(buttons)
            out.writeShort(x.coerceIn(0, serverWidth  - 1))
            out.writeShort(y.coerceIn(0, serverHeight - 1))
            out.flush()
        }
    }

    /** Request a full or incremental framebuffer update */
    fun sendFbUpdateRequest(incremental: Boolean = true) {
        val out = output ?: return
        synchronized(out) {
            sendFbUpdateRequestInternal(out, incremental)
        }
    }

    // ── RFB handshake ─────────────────────────────────────────────────────

    private fun handshake() {
        val inp = input!!
        val out = output!!

        // Read server version
        val serverVer = ByteArray(12).also { inp.readFully(it) }
        val verStr    = String(serverVer)
        TermuxLogger.d("VNC server version: ${verStr.trim()}")

        // Reply with 3.8
        out.write("RFB 003.008\n".toByteArray())
        out.flush()

        // Security types
        val numTypes = inp.readUnsignedByte()
        if (numTypes == 0) {
            val reasonLen = inp.readInt()
            val reason    = ByteArray(reasonLen).also { inp.readFully(it) }
            throw SecurityException("VNC server refused: ${String(reason)}")
        }
        val types = ByteArray(numTypes).also { inp.readFully(it) }

        val useAuth = types.contains(2) && password.isNotEmpty()
        val chosenType = if (useAuth) 2 else 1
        out.writeByte(chosenType)
        out.flush()

        if (chosenType == 2) {
            // VNC Authentication — DES challenge/response
            performVncAuth(inp, out)
        }

        // Security result (only in RFB 3.8+)
        val secResult = inp.readInt()
        if (secResult != 0) {
            val reasonLen = inp.readInt()
            val reason    = ByteArray(reasonLen).also { inp.readFully(it) }
            throw SecurityException("VNC auth failed: ${String(reason)}")
        }
    }

    private fun performVncAuth(inp: DataInputStream, out: DataOutputStream) {
        val challenge = ByteArray(16).also { inp.readFully(it) }
        val response  = desEncryptVnc(challenge, password)
        out.write(response)
        out.flush()
    }

    /**
     * DES encryption for VNC authentication.
     * VNC uses DES with reversed bit order per byte in the key.
     */
    private fun desEncryptVnc(challenge: ByteArray, password: String): ByteArray {
        val key = ByteArray(8)
        val passBytes = password.toByteArray()
        for (i in 0 until minOf(8, passBytes.size)) {
            key[i] = reverseBits(passBytes[i])
        }
        val spec   = javax.crypto.spec.SecretKeySpec(key, "DES")
        val cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, spec)
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var n = b.toInt() and 0xFF
        var r = 0
        repeat(8) { r = (r shl 1) or (n and 1); n = n ushr 1 }
        return r.toByte()
    }

    // ── Server init ───────────────────────────────────────────────────────

    private data class ServerInfo(val width: Int, val height: Int, val name: String)

    private fun serverInit(): ServerInfo {
        val inp = input!!
        val out = output!!

        // ClientInit: shared flag = 1 (allow other clients)
        out.writeByte(1)
        out.flush()

        // ServerInit
        val width  = inp.readUnsignedShort()
        val height = inp.readUnsignedShort()

        // Skip pixel format (16 bytes) — we will override it
        val pixFmt = ByteArray(16).also { inp.readFully(it) }

        val nameLen = inp.readInt()
        val name    = ByteArray(nameLen).also { inp.readFully(it) }

        return ServerInfo(width, height, String(name))
    }

    // ── Pixel format + encodings ──────────────────────────────────────────

    /**
     * Request 32bpp BGRA little-endian.
     * This maps directly to Android's ARGB_8888 Bitmap layout.
     *
     * Layout in memory (LE): B G R 0xFF → 0xFFRRGGBB in int
     * Android ARGB_8888:     B G R A  (same byte order)
     */
    private fun sendSetPixelFormat() {
        val out = output!!
        synchronized(out) {
            out.writeByte(0)     // type: SetPixelFormat
            out.writeByte(0); out.writeByte(0); out.writeByte(0) // padding
            // Pixel format (16 bytes)
            out.writeByte(32)    // bitsPerPixel
            out.writeByte(24)    // depth
            out.writeByte(0)     // bigEndianFlag = false (little endian)
            out.writeByte(1)     // trueColourFlag
            out.writeShort(255)  // redMax
            out.writeShort(255)  // greenMax
            out.writeShort(255)  // blueMax
            out.writeByte(16)    // redShift
            out.writeByte(8)     // greenShift
            out.writeByte(0)     // blueShift
            out.writeByte(0); out.writeByte(0); out.writeByte(0) // padding
            out.flush()
        }
    }

    private fun sendSetEncodings() {
        val out = output!!
        val encodings = intArrayOf(0, 1) // Raw=0, CopyRect=1
        synchronized(out) {
            out.writeByte(2)     // type: SetEncodings
            out.writeByte(0)     // padding
            out.writeShort(encodings.size)
            encodings.forEach { out.writeInt(it) }
            out.flush()
        }
    }

    private fun sendFbUpdateRequestInternal(
        out: DataOutputStream,
        incremental: Boolean,
    ) {
        out.writeByte(3)         // type: FramebufferUpdateRequest
        out.writeByte(if (incremental) 1 else 0)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(serverWidth)
        out.writeShort(serverHeight)
        out.flush()
    }

    // ── Read loop ─────────────────────────────────────────────────────────

    private suspend fun readLoop() {
        val inp = input ?: return
        val out = output ?: return

        while (isActive && _state.value is VncState.Connected) {
            try {
                when (val msgType = inp.readUnsignedByte()) {
                    0    -> handleFramebufferUpdate(inp)
                    1    -> handleSetColourMapEntries(inp)
                    2    -> { /* Bell — ignore */ }
                    3    -> handleServerCutText(inp)
                    else -> TermuxLogger.w("VNC: unknown message type $msgType")
                }
                // Request next incremental update
                synchronized(out) { sendFbUpdateRequestInternal(out, incremental = true) }

            } catch (e: SocketTimeoutException) {
                // Timeout — just keep reading
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (_state.value is VncState.Connected) {
                    TermuxLogger.e("VNC read loop error", e)
                    _state.value = VncState.Failed(e.message ?: "Read error", e)
                }
                break
            }
        }
    }

    // ── Message handlers ──────────────────────────────────────────────────

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.readUnsignedByte()                    // padding
        val numRects = inp.readUnsignedShort()
        val bmp      = bitmap ?: return

        repeat(numRects) {
            val x        = inp.readUnsignedShort()
            val y        = inp.readUnsignedShort()
            val w        = inp.readUnsignedShort()
            val h        = inp.readUnsignedShort()
            val encoding = inp.readInt()

            when (encoding) {
                0    -> decodeRaw(inp, bmp, x, y, w, h)
                1    -> decodeCopyRect(inp, bmp, x, y, w, h)
                else -> TermuxLogger.w("VNC: unsupported encoding $encoding — skipping")
            }
        }
        // Publish updated bitmap
        _framebuffer.value = bmp.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
    }

    private fun decodeRaw(
        inp: DataInputStream,
        bmp: Bitmap,
        x: Int, y: Int, w: Int, h: Int,
    ) {
        val pixels = IntArray(w * h)
        val buf    = ByteArray(w * h * 4)
        inp.readFully(buf)

        var i = 0
        for (p in pixels.indices) {
            val b = buf[i++].toInt() and 0xFF
            val g = buf[i++].toInt() and 0xFF
            val r = buf[i++].toInt() and 0xFF
            val a = buf[i++].toInt() and 0xFF  // unused in 24-depth
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, x, y, w, h)
    }

    private fun decodeCopyRect(
        inp: DataInputStream,
        bmp: Bitmap,
        dstX: Int, dstY: Int, w: Int, h: Int,
    ) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, srcX, srcY, w, h)
        bmp.setPixels(pixels, 0, w, dstX, dstY, w, h)
    }

    private fun handleSetColourMapEntries(inp: DataInputStream) {
        inp.readUnsignedByte()                    // padding
        inp.readUnsignedShort()                   // firstColour
        val count = inp.readUnsignedShort()
        repeat(count) {
            inp.readUnsignedShort()               // red
            inp.readUnsignedShort()               // green
            inp.readUnsignedShort()               // blue
        }
    }

    private fun handleServerCutText(inp: DataInputStream) {
        repeat(3) { inp.readUnsignedByte() }      // padding
        val len  = inp.readInt()
        val text = ByteArray(len).also { inp.readFully(it) }
        TermuxLogger.d("VNC server cut: ${String(text).take(80)}")
        // TODO: copy to Android clipboard
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    private fun closeSocket() {
        runCatching { input?.close()  }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null; output = null; socket = null
    }
}
