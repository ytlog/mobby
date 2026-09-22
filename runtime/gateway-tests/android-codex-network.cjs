'use strict';
// Device gate: app launcher must connect through Android DNS with the bundled CLI.
// MOBBY_TEST_AGENT=CLAUDE selects Claude Code; default is CODEX.
// MOBBY_TEST_TOOL=1 requires an app-UID fixture read (Codex also writes) and result roundtrip.
// MOBBY_TEST_RAW_CODEX=1 diagnoses the unsupported bare Linux binary instead (expected DNS failure).
// Run device gates serially; concurrent app-UID CLI sessions are not a supported Runtime mode.
// Uses an isolated HOME/workspace, fake key, adb reverse and a local mock model only.
const {createServer}=require('node:http');
const {execFile}=require('node:child_process');
const {randomBytes}=require('node:crypto');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const adb=process.env.MOBBY_TEST_ADB || 'adb';
const app='com.github.ytlog.mobby.android';
const mode=process.env.MOBBY_TEST_AGENT || 'CODEX';
assert.ok(['CODEX','CLAUDE'].includes(mode),'Unknown test Agent');
const protocol=mode==='CODEX'?'responses':'messages';
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
    if(req.method!=='POST'||req.url.split('?')[0]!=='/v1/'+protocol){res.writeHead(404);res.end();return;}
    const chunks=[];for await(const chunk of req) chunks.push(chunk);
    const body=JSON.parse(Buffer.concat(chunks));
    if(req.headers.authorization!=='Bearer fake-network-key'){res.writeHead(401);res.end();return;}
    requests++;turnRequests++;
    if (tool && turnRequests>1) {
      toolSeen=JSON.stringify(body).includes('ANDROID_FIXTURE_CONTENT_OK');
      if(!toolSeen) console.log('Synthetic tool result:', JSON.stringify((body.input||[]).filter(i=>i.type==='function_call_output')).slice(0,1800));
    }

    const calls=tool && turnRequests===1?[{id:'call_device_read',type:'function',function:{name:mode==='CODEX'?'exec_command':'Read',arguments:JSON.stringify(mode==='CODEX'?{cmd:'/system/bin/cat '+quote(root+'/workspace/fixture.txt')+' > '+quote(root+'/workspace/result.txt')+' && /system/bin/cat '+quote(root+'/workspace/result.txt'),max_output_tokens:200}:{file_path:root+'/workspace/fixture.txt'})}}]:[];
    sendNative(res,nativeResponse({content:calls.length?'':'ANDROID_NETWORK_SMOKE_OK',calls,input:10,output:4},protocol,'test-model'),protocol,Boolean(body.stream));
  });
  try {
    await checked(['mkdir','-p',root+'/home/.codex',root+'/workspace',root+'/tmp']);
    const env=['env','-i','PREFIX='+prefix,'HOME='+root+'/home','CODEX_HOME='+root+'/home/.codex','TMPDIR='+root+'/tmp',
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
    let sessionId=null;
    for(const {host,resume} of ['127.0.0.1',hostname].flatMap(host =>
      (mode==='CODEX'?[false,true]:[false]).map(resume=>({host,resume})))) {
      const options=['model_provider="probe"','model="gpt-4.1"','model_providers.probe.name="device network test"',
        'model_providers.probe.base_url='+JSON.stringify(`http://${host}:${reversePort}/v1`),
        'model_providers.probe.env_key="FAKE_KEY"','model_providers.probe.wire_api="responses"',
        'model_providers.probe.requires_openai_auth=false','model_providers.probe.supports_websockets=false',
        'model_providers.probe.request_max_retries=0','model_providers.probe.stream_max_retries=0'];
      const before=requests;turnRequests=0;toolSeen=false;
      const raw=process.env.MOBBY_TEST_RAW_CODEX==='1';
      assert.ok(!raw || mode==='CODEX','Raw diagnostic is Codex-only');
      const config=JSON.stringify({endpoint:`http://${host}:${reversePort}/v1`,protocol,model:'test-model',key:'fake-network-key'});
      const launch=raw?[prefix+'/bin/codex',...options.flatMap(v=>['-c',v])]:[prefix+'/bin/node',home+'/files/gateway.cjs',mode,prefix+'/bin/'+(mode==='CODEX'?'codex':'claude')];
      const prompt=tool?(mode==='CODEX'?'Use a tool to read fixture.txt, copy it to result.txt and reply OK.':'Read fixture.txt using a tool and reply OK.'):'Reply OK only. Do not use tools.';
      if(tool && mode==='CODEX') await checked(['rm','-f',root+'/workspace/result.txt']);
      if(resume) assert.match(sessionId??'',/^[A-Za-z0-9-]+$/,'A native session ID is required to test resume');
      const args=mode==='CODEX'?['--sandbox','danger-full-access','-c','approval_policy="never"','exec','-C',root+'/workspace','--json',...(resume?['resume',sessionId]:[]),'--',prompt]:['-p','--output-format','stream-json','--verbose','--',prompt];
      const command=[...env,'MOBBY_GATEWAY_CONFIG='+config,'timeout','-s','TERM','35',...launch,...args];
      const pidFile=root+'/process-group';
      const r=await remote(['/system/bin/toybox','setsid','-w','/system/bin/sh','-c',
        'echo $$ > '+quote(pidFile)+'; cd '+quote(root+'/workspace')+' && exec '+command.map(quote).join(' ')]);
      // CLI background bookkeeping can outlive its main process. End only this fixture's session
      // before the next launch or directory removal; production uses the Runtime process registry.
      const pid=(await remote(['/system/bin/cat',pidFile])).stdout.trim();
      if (/^[1-9][0-9]*$/.test(pid)) await remote(['/system/bin/toybox','kill','-KILL','--','-'+pid]);
      if(r.code!==0) console.log('Isolated CLI launch error:',r.stderr.slice(0,1400));
      const events=r.stdout.split('\n').flatMap(line=>{try{return [JSON.parse(line)];}catch{return [];}});
      if(mode==='CODEX') sessionId=events.find(e=>e.type==='thread.started')?.thread_id;
      const completed=mode==='CODEX'?r.stdout.includes('ANDROID_NETWORK_SMOKE_OK') && events.some(e=>e.type==='turn.completed'):
        events.some(e=>e.type==='result' && e.subtype==='success' && !e.is_error && e.result.includes('ANDROID_NETWORK_SMOKE_OK'));
      const name=host==='127.0.0.1'?'IP':'DNS';
      console.log(`${mode}/${name}/${resume?'resume':'new'}: exit=${r.code}, requests=${requests-before}, completed=${completed}`);
      if(tool) console.log(`Tool roundtrip: ${toolSeen}`);
      if(tool && !toolSeen) console.log('Tool diagnostics:', ['Operation not permitted','bwrap','No such file','Landlock','seccomp','sandbox'].filter(t=>r.stdout.includes(t)||r.stderr.includes(t)).join(', '));
      assert.ok(!tool || toolSeen,'App-UID tool did not return fixture contents');
      if(tool && mode==='CODEX') assert.equal(await checked(['/system/bin/cat',root+'/workspace/result.txt']),'ANDROID_FIXTURE_CONTENT_OK','Tool must actually write the fixture result');
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
