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
                append("Run the bundled helper with this Node binary. Optional arguments are one JSON object.\n\n")
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
const PORT=$port;
const TOKEN=${js(token)};
const PLUGIN=${js(plugin)};
const action=process.argv[2];
if(!action){console.error('usage: <action> [json]');process.exit(2)}
let args={};
if(process.argv[3]){try{args=JSON.parse(process.argv[3])}catch(e){console.error('args must be one JSON object');process.exit(2)}}
const body=JSON.stringify({token:TOKEN,plugin:PLUGIN,action,args});
const socket=net.connect(PORT,'127.0.0.1',()=>socket.end(body+'\n'));
let data='';
socket.on('data',c=>data+=c);
socket.on('end',()=>{
  let parsed;try{parsed=JSON.parse(data)}catch(e){console.error(data||'no response');process.exit(1)}
  if(!parsed.ok){console.error(parsed.error||'failed');process.exit(1)}
  process.stdout.write(String(parsed.result||'')+'\n');
});
socket.setTimeout(120000,()=>{socket.destroy();console.error('device bridge timeout');process.exit(1)});
socket.on('error',e=>{console.error(e.message);process.exit(1)});
""".trimIndent()
    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
