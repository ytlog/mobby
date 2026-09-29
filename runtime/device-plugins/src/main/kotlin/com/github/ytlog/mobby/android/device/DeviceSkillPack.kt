package com.github.ytlog.mobby.android.device

import java.io.File

object DeviceSkillPack {
    fun write(root: File, node: String, port: Int, token: String, refs: Set<String>): List<File> {
        require(node.startsWith("/") && '\u0000' !in node && port in 1..65535)
        return DeviceCatalog.selected(refs).map { spec ->
            val actions = DeviceCatalog.allow(refs)[spec.id].orEmpty()
            val dir = File(root, "skills/${spec.skillName}")
            val scripts = File(dir, "scripts").apply { mkdirs() }
            val helper = File(scripts, "device.cjs")
            helper.writeText(script(port, token, spec.id))
            val skill = File(dir, "SKILL.md")
            val commands = spec.commands.filter { it.action in actions }
            val markdown = buildString {
                append("---\nname: ${spec.skillName}\ndescription: ${yaml(spec.description)}\n---\n\n")
                append(spec.instructions).append("\n\n")
                append("Use mobby.device/1 with this Node binary. Arguments are one JSON object. The helper returns structured JSON, never a card description.\n")
                append("Use --request-id <id> for each action. Reuse that ID only with identical arguments. accepted is not success. After timeout or unconfirmed effects, query status; never resend a write with a new ID.\n")
                append("`$node ${helper.absolutePath} status <original-request-id>` reads only this run.\n")
                append("For a result resourceRef, `$node ${helper.absolutePath} resource '{\"requestId\":\"original-request-id\",\"resourceRef\":\"returned-ref\"}'` exports the verified resource to a local inbox path in result.data.text. No arbitrary paths are accepted.\n\n")
                commands.forEach { command ->
                    append("`$node ${helper.absolutePath} ${command.usage}`\n")
                    if (command.note.isNotBlank()) append(command.note).append('\n')
                    append('\n')
                }
            }
            require(markdown.toByteArray().size <= 128 * 1024)
            skill.writeText(markdown)
            skill
        }
    }
    private fun yaml(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    internal fun script(port: Int, token: String, plugin: String) = """
const net=require('net');
const crypto=require('crypto');
const PORT=$port;
const TOKEN=${js(token)};
const PLUGIN=${js(plugin)};
const argv=process.argv.slice(2);
let requestId;
const flag=argv.indexOf('--request-id');
if(flag>=0){requestId=argv[flag+1];argv.splice(flag,2)}
requestId=requestId||crypto.randomUUID();
if(!/^[A-Za-z0-9._-]{1,128}$/.test(requestId)||argv.length<1||argv.length>2){console.error('usage: <action> [json] [--request-id id]');process.exit(2)}
let action=argv[0],plugin=PLUGIN,args={};
if(action==='status'){plugin='operation';args={requestId:argv[1]}}
else {
  if(action==='resource')plugin='operation';
  if(argv[1]){try{args=JSON.parse(argv[1])}catch(e){console.error('args must be one JSON object');process.exit(2)}}
}
if(args===null||Array.isArray(args)||typeof args!=='object'){console.error('args must be one JSON object');process.exit(2)}
const body=JSON.stringify({token:TOKEN,protocolVersion:1,requestId,plugin,action,args});
if(Buffer.byteLength(body)>65536){console.error('request exceeds 64 KiB');process.exit(2)}
process.stderr.write(JSON.stringify({requestId})+'\n');
const socket=net.connect(PORT,'127.0.0.1',()=>socket.end(body+'\n'));
let data='';
socket.on('data',c=>{data+=c;if(Buffer.byteLength(data)>131072)socket.destroy(new Error('response exceeds limit'))});
socket.on('end',()=>{
  let parsed;try{parsed=JSON.parse(data)}catch(e){console.error(JSON.stringify({requestId,error:'no valid response; query status before any retry'}));process.exit(1)}
  process.stdout.write(JSON.stringify(parsed)+'\n');
  if(!parsed.accepted||['failed','cancelled','unconfirmed','interrupted'].includes(parsed.status))process.exitCode=1;
});
socket.setTimeout(150000,()=>socket.destroy(new Error('timeout; query status with the same requestId, do not resend')));
socket.on('error',e=>{console.error(JSON.stringify({requestId,error:e.message}));process.exitCode=1});
""".trimIndent()
    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
