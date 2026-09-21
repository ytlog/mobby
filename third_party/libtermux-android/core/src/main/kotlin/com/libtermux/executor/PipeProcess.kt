package com.libtermux.executor

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** argv execution, concurrent bounded UTF-8 streams and an isolated process group. */
object PipeProcess {
    init { System.loadLibrary("libtermux_jni") }
    private external fun spawn(args: Array<String>, environment: Array<String>, directory: String, withInput: Boolean): IntArray?
    private external fun poll(pid: Int): Int
    private external fun reap(pid: Int)
    private external fun signalGroup(pid: Int, signal: Int)

    fun stream(args: List<String>, directory: File, environment: Map<String, String>, timeoutMs: Long,
        onStarted: (Int) -> Unit = {}, onTerminated: (Int?) -> Unit = {},
        input: Flow<ByteArray>? = null): Flow<OutputLine> = channelFlow {
        require(args.isNotEmpty() && args.none { '\u0000' in it })
        val child = spawn(args.toTypedArray(), environment.map { "${it.key}=${it.value}" }.toTypedArray(), directory.absolutePath, input != null)
            ?: error("Unable to create runtime process")
        val pid = child[0]
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[1]))
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[2]))
        val stdin = if (child[3] >= 0) ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(child[3])) else null
        val failure = AtomicReference<Throwable?>()
        fun reader(input: InputStream, error: Boolean) = thread(isDaemon = true) {
            try {
                readBoundedLines(input) { line ->
                    if (trySendBlocking(if (error) OutputLine.Stderr(line) else OutputLine.Stdout(line)).isFailure) throw java.io.IOException("Stream closed")
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }
        val readers = listOf(reader(stdout, false), reader(stderr, true))
        val writer = if (input != null && stdin != null) launch(Dispatchers.IO) {
            try {
                stdin.use { output -> input.collect { bytes -> output.write(bytes); output.flush() } }
            } catch (e: CancellationException) {
                // Upstream cancellation is a failure; our own exit/cleanup cancellation is expected.
                if (currentCoroutineContext().isActive) failure.compareAndSet(null, e)
            } catch (e: Throwable) { failure.compareAndSet(null, e) }
        } else null
        var reaped = false
        var exitCode: Int? = null
        try {
            onStarted(pid)
            withTimeout(timeoutMs) {
                var exit: Int
                while (true) {
                    failure.get()?.let { throw it }
                    exit = poll(pid)
                    check(exit != -2) { "Cannot observe runtime process exit" }
                    if (exit >= 0) { reaped = true; exitCode = exit; break }
                    delay(25)
                }
                // A completed task must not leave ordinary descendants holding the pipes open.
                signalGroup(pid, 9)
                writer?.cancelAndJoin()
                while (readers.any { it.isAlive }) { delay(10) }
                failure.get()?.let { throw it }
                send(OutputLine.Exit(exit))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                writer?.cancel()
                signalGroup(pid, 15)
                if (!reaped) {
                    repeat(20) {
                        if (!reaped) { val observed = poll(pid); reaped = observed >= 0; if (reaped) exitCode = observed; if (!reaped) delay(25) }
                    }
                }
                signalGroup(pid, 9)
                if (!reaped) { repeat(40) { if (!reaped) { val observed = poll(pid); reaped = observed >= 0; if (reaped) exitCode = observed; if (!reaped) delay(25) } } }
                stdin?.close(); stdout.close(); stderr.close()
                writer?.join()
                readers.forEach { it.join(200) }
                if (reaped) reap(pid)
                onTerminated(exitCode)
                check(reaped) { "Could not confirm runtime process termination" }
            }
        }
    }
}

internal fun readBoundedLines(input: InputStream, consume: (String) -> Unit) {
    input.reader(Charsets.UTF_8).use { reader ->
        val line = StringBuilder()
        val buffer = CharArray(4096)
        var truncated = false
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                val c = buffer[i]
                if (c == '\n') {
                    consume(line.toString().trimEnd('\r') + if (truncated) " [line truncated]" else "")
                    line.setLength(0); truncated = false
                } else if (line.length < 262144) line.append(c) else truncated = true
            }
        }
        if (line.isNotEmpty() || truncated) consume(line.toString() + if (truncated) " [line truncated]" else "")
    }
}
