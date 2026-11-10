'use strict';
// Requires installed app + androidTest APK. Synthetic gateway only, no user configuration.
// Codex marks some models code_mode_only: every tool runs as JavaScript in codex-code-mode-host.
// The mock asks for one such cell that calls the shell tool and then checks the shell result came back.
// MOBBY_TEST_MODEL=<model without tool_mode> (e.g. deepseek/deepseek-v4.1-flash-1017) checks that Direct tools still
// work while the gateway runs the host beside every app-server.
const {createServer}=require('node:http');
const {execFile}=require('node:child_process');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const adb=process.env.MOBBY_TEST_ADB||'adb';
const model=process.env.MOBBY_TEST_MODEL||'gpt-5.6-sol';
const shell='printf ANDROID_CODE_MODE_SHELL_OK > result.txt && cat result.txt';
const command=args=>new Promise(resolve=>execFile(adb,args,{timeout:300000,maxBuffer:4*1024*1024},(error,stdout,stderr)=>resolve({code:error?.code??0,stdout,stderr})));
const code='const result = await tools.exec_command({ cmd: '+JSON.stringify(shell)+' });\n'+
  'text(JSON.stringify(result));';
async function main() {
  let requests=0,codeModeTools=false,directTools=false,shellResult=false,port;
  const server=createServer(async(req,res)=>{
    if(req.method!=='POST'||req.url!=='/v1/responses'){res.writeHead(404);res.end();return;}
    const chunks=[];for await(const c of req)chunks.push(c);
    const body=JSON.parse(Buffer.concat(chunks));requests++;
    assert.equal(req.headers.authorization,'Bearer fake-codex-device-key');
    // Codex 0.155.1 sends these models' tools as an additional_tools input item. Code mode offers the
    // custom `exec` tool and keeps the shell (exec_command) reachable only from inside the JavaScript cell.
    const offered=(body.input||[]).filter(item=>item.type==='additional_tools').flatMap(item=>item.tools||[])
      .flatMap(tool=>tool.type==='namespace'?tool.tools||[]:[tool]);
    codeModeTools ||= offered.some(tool=>tool.type==='custom'&&tool.name==='exec') && !offered.some(tool=>tool.name==='exec_command');
    directTools ||= (body.tools||[]).some(tool=>tool.name==='exec_command');
    const output=(body.input||[]).find(item=>/^(custom_tool_call|function_call)_output$/.test(item.type)&&item.call_id==='call_shell');
    if(output) shellResult ||= JSON.stringify(output).includes('ANDROID_CODE_MODE_SHELL_OK');
    const call=codeModeTools?{name:'exec',arguments:JSON.stringify({input:code})}:{name:'exec_command',arguments:JSON.stringify({cmd:shell})};
    const calls=output?[]:[{id:'call_shell',type:'function',function:call}];
    if(output) console.log('Shell tool output:',JSON.stringify(output).slice(0,600));
    sendNative(res,nativeResponse({content:calls.length?'':'ANDROID_CODE_MODE_DONE',calls,input:20,output:8},'responses',model,new Set(['exec'])),'responses',Boolean(body.stream));
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');
  try {
    const reversed=await command(['reverse','tcp:0','tcp:'+server.address().port]);
    assert.equal(reversed.code,0);port=reversed.stdout.trim();assert.match(port,/^\d+$/);
    const endpoint=`http://127.0.0.1:${port}/v1`;
    const result=await command(['shell','am','instrument','-w','-e','class','com.github.ytlog.mobby.android.CodexCodeModeDeviceTest',
      '-e','codexMockEndpoint',endpoint,'-e','codexModel',model,'com.github.ytlog.mobby.android.test/androidx.test.runner.AndroidJUnitRunner']);
    assert.equal(result.code,0,result.stderr+result.stdout);
    assert.match(result.stdout,/OK \(1 test\)/,result.stdout);
    const expectCodeMode=process.env.MOBBY_TEST_MODEL===undefined||/^gpt-(6|5\.6)/.test(model);
    assert.ok(requests>=2 && shellResult && (expectCodeMode?codeModeTools:directTools&&!codeModeTools),
      JSON.stringify({requests,codeModeTools,directTools,shellResult}));
    console.log(`PASS Android Codex ${model}: ${codeModeTools?'code-mode-only tools ran a real shell command through the packaged host':'Direct shell tool still works beside the host'}`);
  } finally {
    server.closeAllConnections();server.close();
    if(port)await command(['reverse','--remove','tcp:'+port]);
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
