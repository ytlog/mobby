'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {once}=require('node:events');
const {endpoint,createBridge,agentLaunch}=require('../../runtime-android/src/main/assets/gateway/bridge.cjs');
async function mock(handler) {
  const server=http.createServer(async(req,res)=>{const chunks=[];for await(const c of req)chunks.push(c);await handler(req,res,JSON.parse(Buffer.concat(chunks).toString()||'{}'));});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  return {url:`http://127.0.0.1:${server.address().port}/v1`,close:()=>{server.closeAllConnections();server.close();}};
}
test('unsupported gateway protocol is refused before any Agent launches',async()=>{
  await assert.rejects(async()=>{const bridge=await createBridge({endpoint:'https://example.invalid',protocol:'chat',model:'m',key:''});bridge.close();},/协议/);
  for(const mode of ['CODEX','CLAUDE']) {
    assert.throws(()=>agentLaunch(mode,[],{protocol:mode==='CODEX'?'messages':'responses'}, {}, {url:'http://127.0.0.1:3000',token:'local'}),/协议/);
  }
});
test('all Agent/protocol combinations use only the authenticated local bridge',()=>{
  const {agentLaunch}=require('../../runtime-android/src/main/assets/gateway/bridge.cjs');
  const bridge={url:'http://127.0.0.1:32123',token:'local-only-token'};
  for(const mode of ['CLAUDE','CODEX']) for(const protocol of [mode==='CLAUDE'?'messages':'responses']) {
    const config={endpoint:'https://gateway.example/v1',protocol,model:'m',key:'upstream-secret'};
    const launch=agentLaunch(mode,['exec'],config,{MOBBY_GATEWAY_CONFIG:JSON.stringify(config)},bridge);
    assert.ok(!JSON.stringify(launch).includes('upstream-secret'));
    assert.ok(!JSON.stringify(launch).includes('gateway.example'));
    if(mode==='CLAUDE') {assert.equal(launch.env.ANTHROPIC_BASE_URL,bridge.url);assert.equal(launch.env.ANTHROPIC_AUTH_TOKEN,bridge.token);}
    else {assert.ok(launch.args.includes('model_providers.mobby.base_url="'+bridge.url+'/v1"'));assert.equal(launch.env.MOBBY_GATEWAY_TOKEN,bridge.token);
      assert.equal(launch.args.some(a=>a.includes('model_auto_compact_token_limit')),protocol!=='responses');}
    assert.throws(()=>agentLaunch(mode,[],config,{}),/本地桥接/);
  }
});

test('same-protocol auxiliary requests preserve body, query and native headers',async()=>{
  for(const [protocol,suffix] of [['responses','/compact'],['messages','/count_tokens']]) {
    const body={model:'original',input:[{type:'compaction',encrypted_content:'opaque'}],messages:[],custom_field:{retain:true}};
    const upstream=await mock((req,res,received)=>{
      assert.equal(req.url,'/v1/'+protocol+suffix+'?beta=true');
      assert.deepEqual(received,{...body,model:'configured'});
      assert.equal(req.headers.authorization,'Bearer upstream-secret');
      assert.equal(req.headers['openai-beta'],protocol==='responses'?'responses=experimental':undefined);
      assert.equal(req.headers['anthropic-beta'],protocol==='messages'?'token-counting-test':undefined);
      assert.equal(req.headers['x-api-key'],protocol==='messages'?'upstream-secret':undefined);
      res.setHeader('content-type','application/json');res.setHeader('x-codex-turn-state','opaque-state');res.end('{"opaque_result":true}');
    });
    const bridge=await createBridge({endpoint:upstream.url,protocol,model:'configured',key:'upstream-secret'});
    try {
      const result=await fetch(bridge.url+'/v1/'+protocol+suffix+'?beta=true',{method:'POST',headers:{authorization:'Bearer '+bridge.token,'openai-beta':'responses=experimental','anthropic-beta':'token-counting-test'},body:JSON.stringify(body)});
      assert.equal(result.status,200);assert.equal(result.headers.get('x-codex-turn-state'),'opaque-state');assert.deepEqual(await result.json(),{opaque_result:true});
    } finally {bridge.close();upstream.close();}
  }
});

test('controlled stdin file is opened without following symlinks and input path is not forwarded',()=>{
  const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
  const {openAgentInput}=require('../../runtime-android/src/main/assets/gateway/bridge.cjs');
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mobby-input-'));
  try {
    const file=path.join(root,'input.jsonl'); fs.writeFileSync(file,'{"message":"图像"}\n');
    const descriptor=openAgentInput(file);
    try {assert.equal(fs.readFileSync(descriptor,'utf8'),'{"message":"图像"}\n');} finally {fs.closeSync(descriptor);}
    const symlink=path.join(root,'link');fs.symlinkSync(file,symlink);
    assert.throws(()=>openAgentInput(symlink));assert.throws(()=>openAgentInput(root));
    const launch=require('../../runtime-android/src/main/assets/gateway/bridge.cjs').agentLaunch('CLAUDE',[],{endpoint:'https://example.com/v1',protocol:'messages',model:'m',key:'fake'},{MOBBY_AGENT_INPUT_FILE:file},{url:'http://127.0.0.1:32123',token:'test-token'});
    assert.equal(launch.env.MOBBY_AGENT_INPUT_FILE,undefined);
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});

test('native payloads keep images, encrypted reasoning, tools and extra fields; SSE is byte faithful',async()=>{
  for(const protocol of ['responses','messages']) {
    const body={model:'ignored',stream:true,input:[{type:'reasoning',encrypted_content:'opaque'},{role:'user',content:[{type:'input_image',image_url:'data:image/png;base64,iVBORw0KGgo=',detail:'high'}]}],messages:[{role:'user',content:[{type:'image',source:{type:'base64',media_type:'image/png',data:'iVBORw0KGgo='}}]}],tools:[{type:'custom',name:'apply_patch',format:{type:'text'}}],future_field:{keep:'中文'}};
    const bytes='event: future.event\ndata: {"opaque":"中文"}\n\nevent: response.incomplete\ndata: {"reason":"max_output_tokens"}\n\n';
    const upstream=await mock((req,res,received)=>{
      assert.deepEqual(received,{...body,model:'configured'});
      assert.equal(req.headers.authorization,undefined);assert.equal(req.headers['x-api-key'],undefined);
      res.writeHead(200,{'content-type':'text/event-stream'});res.end(bytes);
    });
    const bridge=await createBridge({endpoint:upstream.url,protocol,model:'configured',key:''});
    try {
      const result=await fetch(bridge.url+'/v1/'+protocol,{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify(body)});
      assert.equal(await result.text(),bytes);
    } finally {bridge.close();upstream.close();}
  }
});
test('unauthorized and mismatched requests never reach upstream',async()=>{
  let requests=0;
  const upstream=await mock((req,res)=>{requests++;res.end('{}');});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:'fake-secret'});
  try {
    for(const [path,token,status] of [['/v1/responses','wrong',401],['/v1/messages',bridge.token,400],['/arbitrary',bridge.token,404]]) {
      const result=await fetch(bridge.url+path,{method:'POST',headers:{authorization:'Bearer '+token},body:'{}'});
      assert.equal(result.status,status);await result.text();
    }
    assert.equal(requests,0);
  } finally {bridge.close();upstream.close();}
});
test('upstream HTTP errors redact bodies and redirects are not followed',async()=>{
  for(const status of [302,401,429,500]) {
    const upstream=await mock((req,res)=>{res.writeHead(status,{location:'http://127.0.0.1:1/secret'});res.end('fake-secret');});
    const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:'fake-secret'});
    try {
      const result=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:'{}'});
      assert.equal(result.status,status===302?502:status);assert.ok(!(await result.text()).includes('fake-secret'));
    } finally {bridge.close();upstream.close();}
  }
});
test('broken upstream SSE closes the response without appending a success or JSON error',async()=>{
  const upstream=await mock((req,res)=>{res.writeHead(200,{'content-type':'text/event-stream'});res.write('event: response.created\ndata: {}\n\n');setTimeout(()=>res.destroy(),20);});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:''});
  try {const result=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:'{}'});await assert.rejects(result.text());}
  finally {bridge.close();upstream.close();}
});
test('cancelling a client stream terminates the upstream request',async()=>{
  let closed;
  const closure=new Promise(resolve=>{closed=resolve;});
  const upstream=await mock((req,res)=>{res.on('close',closed);res.writeHead(200,{'content-type':'text/event-stream'});res.write('event: response.created\ndata: {}\n\n');});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:''});
  try {
    const result=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:'{}'});
    await result.body.cancel();
    let timer;try {await Promise.race([closure,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('upstream stayed open')),2000);})]);}finally{clearTimeout(timer);}
  } finally {bridge.close();upstream.close();}
});
test('malformed or compressed input is explicitly refused before upstream',async()=>{
  let requests=0;const upstream=await mock((req,res)=>{requests++;res.end('{}');});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:''});
  try {
    for(const [body,extra,status] of [['bad',{},400],['[]',{},400],['{}',{'content-encoding':'zstd'},415],['x'.repeat(16*1024*1024+1),{},400]]) {
      const result=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token,...extra},body});
      assert.equal(result.status,status);await result.text();
    }
    assert.equal(requests,0);
  } finally {bridge.close();upstream.close();}
});
test('gateway paths normalize native root, version, full and custom paths',()=>{
  for(const protocol of ['responses','messages']) {
    for(const address of ['https://example.com','https://example.com/v1/','https://example.com/v1/'+protocol]) assert.equal(endpoint({endpoint:address,protocol}),'https://example.com/v1/'+protocol);
    assert.equal(endpoint({endpoint:'https://example.com/api/custom',protocol}),'https://example.com/api/custom/'+protocol);
  }
});
