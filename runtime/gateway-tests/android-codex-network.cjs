'use strict';
// Device gate: app launcher must connect through Android DNS with the bundled Codex.
// MOBBY_TEST_TOOL=1 additionally requires a sandboxed fixture read and result roundtrip.
// MOBBY_TEST_RAW_CODEX=1 diagnoses the unsupported bare Linux binary instead (expected DNS failure).
// Uses an isolated HOME/workspace, fake key, adb reverse and a local mock model only.
const {createServer}=require('node:http');
const {execFile}=require('node:child_process');
const {randomBytes}=require('node:crypto');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const adb=process.env.MOBBY_TEST_ADB || 'adb';
const app='com.mdoer.app';
const hostname=process.env.MOBBY_TEST_LOOPBACK_DNS || '127.0.0.1.nip.io';
const quote=value=>"'"+String(value).replaceAll("'","'\\''")+"'";
function command(args) {
  return new Promise(resolve=>{
    const child=execFile(adb,args,{timeout:45000,maxBuffer:2*1024*1024},(error,stdout,stderr)=>resolve({code:error?.code??0,stdout,stderr}));
    child.stdin.end();
  });
}
const remote=args=>command(['shell',['run-as',app,...args].map(quote).join(' ')]);
async function checked(args) { const r=await remote(args); assert.equal(r.code,0,'Device fixture setup failed'); return r.stdout.trim(); }
async function main() {
  assert.match(hostname,/^[a-zA-Z0-9.-]+$/);
  const home=await checked(['pwd']);
  const root=home+'/files/network-smoke-'+randomBytes(6).toString('hex');
  const prefix=home+'/files/libtermux/usr';
  const native=(await checked(['readlink',prefix+'/bin/codex'])).replace(/\/[^/]+$/,'');
  let reversePort=null,requests=0,turnRequests=0,toolSeen=false;
  const tool=process.env.MOBBY_TEST_TOOL==='1';
  const server=createServer(async(req,res)=>{
    if(req.method!=='POST'||req.url!=='/v1/responses'){res.writeHead(404);res.end();return;}
    const chunks=[];for await(const chunk of req) chunks.push(chunk);
    const body=JSON.parse(Buffer.concat(chunks));
    if(req.headers.authorization!=='Bearer fake-network-key'){res.writeHead(401);res.end();return;}
    requests++;turnRequests++;
    if (tool && turnRequests>1) {
      toolSeen=JSON.stringify(body).includes('ANDROID_FIXTURE_CONTENT_OK');
      if(!toolSeen) console.log('Synthetic tool result:', JSON.stringify((body.input||[]).filter(i=>i.type==='function_call_output')).slice(0,1800));
    }
    if(tool && turnRequests===1) console.log('Offered tools:',(body.tools||[]).map(t=>t.name||t.type).join(', '));
    const calls=tool && turnRequests===1?[{id:'call_device_read',type:'function',function:{name:'exec_command',arguments:JSON.stringify({cmd:'/system/bin/cat '+quote(root+'/workspace/fixture.txt'),max_output_tokens:200})}}]:[];
    sendNative(res,nativeResponse({content:calls.length?'':'ANDROID_NETWORK_SMOKE_OK',calls,input:10,output:4},'responses','gpt-4.1'),'responses',Boolean(body.stream));
  });
  try {
    await checked(['mkdir','-p',root+'/home/.codex',root+'/workspace',root+'/tmp']);
    const env=['env','-i','HOME='+root+'/home','CODEX_HOME='+root+'/home/.codex','TMPDIR='+root+'/tmp',
      'PATH='+prefix+'/bin:/system/bin','LD_LIBRARY_PATH='+prefix+'/lib:'+native,
      'SHELL='+prefix+'/bin/bash','SSL_CERT_FILE='+prefix+'/etc/tls/cert.pem','GIT_CONFIG_NOSYSTEM=1','GIT_TEMPLATE_DIR='+prefix+'/share/git-core/templates',
      'FAKE_KEY=fake-network-key','NO_COLOR=1','DISABLE_AUTOUPDATER=1'];
    await checked([...env,prefix+'/bin/git','init','-q',root+'/workspace']);
    if(tool) await checked([...env,prefix+'/bin/node','-e',`require('fs').writeFileSync(${JSON.stringify(root+'/workspace/fixture.txt')},'ANDROID_FIXTURE_CONTENT_OK')`]);
    const address=await checked([...env,prefix+'/bin/node','-e',`require('dns').lookup(${JSON.stringify(hostname)},(e,a)=>{if(e)process.exit(2);console.log(a)})`]);
    assert.equal(address,'127.0.0.1','Test DNS must resolve to loopback with Android Node before comparing Codex');
    console.log('PASS Android Node resolves test hostname to loopback');
    server.listen(0,'127.0.0.1');await once(server,'listening');
    const reverse=await command(['reverse','tcp:0','tcp:'+server.address().port]);
    assert.equal(reverse.code,0,'adb reverse unavailable');
    reversePort=reverse.stdout.trim();assert.match(reversePort,/^\d+$/);
    for(const host of ['127.0.0.1',hostname]) {
      const options=['model_provider="probe"','model="gpt-4.1"','model_providers.probe.name="device network test"',
        'model_providers.probe.base_url='+JSON.stringify(`http://${host}:${reversePort}/v1`),
        'model_providers.probe.env_key="FAKE_KEY"','model_providers.probe.wire_api="responses"',
        'model_providers.probe.requires_openai_auth=false','model_providers.probe.supports_websockets=false',
        'model_providers.probe.request_max_retries=0','model_providers.probe.stream_max_retries=0'];
      const before=requests;turnRequests=0;toolSeen=false;
      const raw=process.env.MOBBY_TEST_RAW_CODEX==='1';
      const config=JSON.stringify({endpoint:`http://${host}:${reversePort}/v1`,protocol:'responses',model:'gpt-4.1',key:'fake-network-key'});
      const launch=raw?[prefix+'/bin/codex',...options.flatMap(v=>['-c',v])]:[prefix+'/bin/node',home+'/files/gateway.cjs','CODEX',prefix+'/bin/codex'];
      const r=await remote([...env,'MOBBY_GATEWAY_CONFIG='+config,'timeout','-s','TERM','35',...launch,
        'exec','-C',root+'/workspace','--json','--',tool?'Read fixture.txt using a shell tool and reply OK.':'Reply OK only. Do not use tools.']);
      const completed=r.stdout.includes('ANDROID_NETWORK_SMOKE_OK') && r.stdout.includes('turn.completed');
      const name=host==='127.0.0.1'?'IP':'DNS';
      console.log(`${name}: exit=${r.code}, requests=${requests-before}, completed=${completed}`);
      if(tool) console.log(`Tool roundtrip: ${toolSeen}`);
      if(tool && !toolSeen) console.log('Tool diagnostics:', ['Operation not permitted','bwrap','No such file','Landlock','seccomp','sandbox'].filter(t=>r.stdout.includes(t)||r.stderr.includes(t)).join(', '));
      assert.ok(!tool || toolSeen,'Sandboxed tool did not return fixture contents');
      assert.ok(r.code===0 && completed && requests>before,`${name} route failed; Codex launch route is not device-network ready`);
    }
  } finally {
    server.closeAllConnections();server.close();
    if(reversePort) await command(['reverse','--remove','tcp:'+reversePort]);
    // Only the unique directory created by this test; never the user's HOME or workspace.
    const cleanup=await remote(['rm','-rf',root]);
    assert.equal(cleanup.code,0,'Device test fixture cleanup failed');
  }
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
