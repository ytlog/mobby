package com.mobby.runtime.android

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class PhoneCommandServer(private val token: String, private val operator: PhoneOperator) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    private val worker = thread(name = "phone-bridge", isDaemon = true) {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            socket.soTimeout = 8_000
            socket.use { active ->
                val line = BufferedReader(InputStreamReader(active.getInputStream())).readLine() ?: return@use
                val response = PhoneCommands.handle(line, token, operator)
                active.getOutputStream().write((response + "\n").toByteArray())
            }
        }
    }
    override fun close() {
        running.set(false)
        runCatching { server.close() }
        worker.join(500)
    }

    companion object {
        fun helper(directory: File, port: Int, token: String): File {
            directory.mkdirs()
            val file = File(directory, "phone.cjs")
            file.writeText(
                """
const net=require('net');
const action=process.argv[2];
if(!action){console.error('usage: snapshot|click|type|tap|back|home|recents');process.exit(1)}
const body={token:${jsString(token)},action};
if(action==='click')body.query=process.argv.slice(3).join(' ');
else if(action==='type')body.text=process.argv.slice(3).join(' ');
else if(action==='tap'){body.x=process.argv[3];body.y=process.argv[4];}
const socket=net.connect(${port},'127.0.0.1',()=>{socket.end(JSON.stringify(body)+'\n')});
let data='';socket.on('data',c=>data+=c);socket.on('end',()=>{
  let parsed;try{parsed=JSON.parse(data)}catch{console.error(data||'no response');process.exit(1)}
  if(!parsed.ok){console.error(parsed.error||'failed');process.exit(1)}
  console.log(parsed.result);process.exit(0);
});
socket.setTimeout(8000,()=>{console.error('phone bridge timeout');process.exit(1)});
""".trimIndent()
            )
            return file
        }
        private fun jsString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
