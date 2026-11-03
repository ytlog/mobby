'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const net=require('node:net');
const {once}=require('node:events');
const {createNativeProxy,nativeLaunch}=require('../android/src/main/assets/gateway/native-network.cjs');

function connect(proxy, destination, authenticated=true) {
  const url=new URL(proxy.url);
  const authorization='Basic '+Buffer.from(`${url.username}:${url.password}`).toString('base64');
  const req=http.request({host:url.hostname,port:url.port,method:'CONNECT',path:destination,
    headers:authenticated?{'proxy-authorization':authorization}:{}});
  const result=new Promise((resolve,reject)=>{req.once('connect',(response,socket,head)=>resolve({response,socket,head}));req.once('error',reject);});
  req.end();return result;
}
test('native transport requires authentication and rejects non-TLS destinations before connecting',async()=>{
  let reached=false;
  const proxy=await createNativeProxy({connect(){reached=true;throw Error('unexpected network');}});
  try {
    for(const [destination,authenticated,status] of [['example.invalid:443',false,407],['example.invalid:80',true,403],['example.invalid:443/path',true,403],['user:password@example.invalid:443',true,403]]) {
      const result=await connect(proxy,destination,authenticated);
      assert.equal(result.response.statusCode,status);result.socket.destroy();
    }
    assert.equal(reached,false);
  }finally{proxy.close();}
});
test('native tunnel preserves exact binary bytes and does not convert protocol bodies or forward proxy credentials',async()=>{
  const echo=net.createServer(socket=>socket.pipe(socket));echo.listen(0,'127.0.0.1');await once(echo,'listening');
  let target;
  const proxy=await createNativeProxy({connect(options){target=options;return net.connect(echo.address().port,'127.0.0.1');}});
  try {
    const {response,socket}=await connect(proxy,'official.example:443');assert.equal(response.statusCode,200);
    assert.deepEqual(target,{host:'official.example',port:443});
    const payload=Buffer.concat([Buffer.from([0,255,1,3]),Buffer.from(JSON.stringify({tools:[{unknown:42}],image:'untouched',reasoning:{budget:9}}))]);
    const data=once(socket,'data');socket.write(payload);assert.deepEqual((await data)[0],payload);socket.destroy();
  } finally {proxy.close();await new Promise(resolve=>echo.close(resolve));}
});
test('Codex native execution uses its original account provider and never inherits gateway API authentication',async()=>{
  const original={HOME:'/private/test-home',OPENAI_API_KEY:'synthetic-old-api-key',ANTHROPIC_AUTH_TOKEN:'synthetic-old-token',
    MOBBY_GATEWAY_CONFIG:'synthetic-old-gateway',CODEX_HOME:'/private/test-vault'};
  const launch=await nativeLaunch('CODEX',['app-server'],{model:'account-model',provider:'openai'},original);
  try {
    assert.equal(launch.env.OPENAI_API_KEY,undefined);assert.equal(launch.env.ANTHROPIC_AUTH_TOKEN,undefined);
    assert.equal(launch.env.MOBBY_GATEWAY_CONFIG,undefined);assert.equal(launch.env.CODEX_HOME,original.CODEX_HOME);
    assert.ok(launch.args.includes('model_provider="openai"'));assert.ok(launch.args.includes('cli_auth_credentials_store="file"'));
    assert.ok(!launch.args.join(' ').includes('requires_openai_auth=false'));assert.ok(!launch.args.join(' ').includes('base_url'));
    assert.equal(original.OPENAI_API_KEY,'synthetic-old-api-key');
  }finally{launch.proxy.close();}
});
test('Pi native mode uses the selected provider and OpenCode retains built-in OAuth plugins',async()=>{
  const pi=await nativeLaunch('PI',['--mode','rpc','--provider','mobby','--model','native-model'],{model:'native-model',provider:'openai-codex'},{});
  const open=await nativeLaunch('OPEN_CODE',['serve'],{model:'native-model',provider:'openai'},{OPENCODE_DISABLE_DEFAULT_PLUGINS:'1'});
  try {
    assert.equal(pi.args[pi.args.indexOf('--provider')+1],'openai-codex');assert.equal(pi.env.MOBBY_PI_CONFIG,undefined);
    assert.equal(open.env.OPENCODE_DISABLE_DEFAULT_PLUGINS,undefined);assert.equal(open.env.OPENAI_API_KEY,undefined);
    assert.equal(open.env.MOBBY_NATIVE_PROVIDER,'openai');
  }finally{pi.proxy.close();open.proxy.close();}
});

const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {spawn}=require('node:child_process');
const authScript=path.resolve(__dirname,'../android/src/main/assets/gateway/native-auth.cjs');
async function authFixture(mode,cli,extra={}) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mobby-auth-contract-'));
  const executable=path.join(root,'agent');
  fs.writeFileSync(executable,`#!${process.execPath}\n${cli}`,{mode:0o700});
  const credentialDirectory=path.join(root,'credentials');fs.mkdirSync(credentialDirectory);
  const env={HOME:root,PATH:process.env.PATH,CODEX_HOME:credentialDirectory,CLAUDE_CONFIG_DIR:credentialDirectory,
    XDG_DATA_HOME:root,...extra};
  const processChild=spawn(process.execPath,[authScript,'login',mode,mode==='CODEX'?'chatgpt':mode==='CLAUDE'?'claudeai':'0',executable],{env});
  let stdout='',stderr='';processChild.stdout.on('data',data=>{stdout+=data;});processChild.stderr.on('data',data=>{stderr+=data;});
  const timer=setTimeout(()=>processChild.kill('SIGKILL'),10000);
  try {const [code]=await once(processChild,'exit');return {code,stdout,stderr,events:stdout.trim().split('\n').map(line=>JSON.parse(line))};}
  finally {clearTimeout(timer);fs.rmSync(root,{recursive:true,force:true});}
}
test('Codex sign-in calls its app-server login and model APIs and exposes no credential or account email',async()=>{
  const result=await authFixture('CODEX',`
const fs=require('fs'),readline=require('readline');
readline.createInterface({input:process.stdin}).on('line',line=>{
 const value=JSON.parse(line);if(value.id===undefined)return;
 let result={};
 if(value.method==='account/login/start') {
  if(value.params.type!=='chatgpt')process.exit(2);
  fs.writeFileSync(process.env.CODEX_HOME+'/auth.json',JSON.stringify({tokens:{access_token:'synthetic-private-native-token'}}));
  result={authUrl:'https://auth.openai.com/authorize?state=synthetic',loginId:'fixture'};
 }
 if(value.method==='account/read')result={account:{type:'chatgpt',email:'private-fixture@example.invalid'}};
 if(value.method==='model/list')result={data:[{model:'account-model',displayName:'Account model'}],nextCursor:null};
 console.log(JSON.stringify({id:value.id,result}));
 if(value.method==='account/login/start')console.log(JSON.stringify({method:'account/login/completed',params:{success:true}}));
});`);
  assert.equal(result.code,0);assert.equal(result.stderr,'');
  assert.equal(result.events.find(event=>event.type==='account').provider,'openai');
  assert.deepEqual(result.events.find(event=>event.type==='account').models,[{id:'account-model',name:'Account model'}]);
  assert.ok(result.events.some(event=>event.type==='update'&&event.url.startsWith('https://auth.openai.com/')));
  assert.ok(!result.stdout.includes('synthetic-private-native-token'));assert.ok(!result.stdout.includes('private-fixture@example.invalid'));
});
test('Claude login invokes auth login and status while keeping raw CLI diagnostics outside the UI protocol',async()=>{
  const result=await authFixture('CLAUDE',`
const fs=require('fs');
if(process.argv.slice(2).join(' ')==='auth login --claudeai') {
 fs.writeFileSync(process.env.CLAUDE_CONFIG_DIR+'/.credentials.json',JSON.stringify({claudeAiOauth:{accessToken:'synthetic-private-native-token'}}));
 console.log('A raw diagnostic that must not be forwarded: synthetic-private-native-token');
 console.log("If the browser didn't open, visit: https://claude.ai/oauth/authorize?state=synthetic");
}else if(process.argv.slice(2).join(' ')==='auth status --json')console.log(JSON.stringify({loggedIn:true,authMethod:'claude.ai'}));
else process.exit(2);`);
  assert.equal(result.code,0);assert.equal(result.events.find(event=>event.type==='account').provider,'anthropic');
  assert.ok(result.events.some(event=>event.type==='update'&&event.url.startsWith('https://claude.ai/')));
  assert.ok(!result.stdout.includes('synthetic-private-native-token'));assert.ok(!result.stdout.includes('raw diagnostic'));
});

test('OpenCode sign-in uses its built-in provider OAuth endpoints and preserves device instructions',async()=>{
  const result=await authFixture('OPEN_CODE',`
const fs=require('fs'),http=require('http');let loggedIn=false;
const port=Number(process.argv[process.argv.indexOf('--port')+1]);
const expected='Basic '+Buffer.from('opencode:'+process.env.OPENCODE_SERVER_PASSWORD).toString('base64');
http.createServer(async(req,res)=>{
 if(req.headers.authorization!==expected){res.writeHead(401);res.end();return;}
 let raw='';for await(const chunk of req)raw+=chunk;
 const body=raw?JSON.parse(raw):{};let response;
 if(req.url==='/provider/auth')response={openai:[{type:'oauth',label:'ChatGPT native device code'},{type:'api',label:'API key'}]};
 else if(req.url==='/provider/openai/oauth/authorize'){
  if(body.method!==0)process.exit(2);
  response={url:'https://auth.openai.com/codex/device',method:'auto',instructions:'Enter synthetic-device-code on the page'};
 }else if(req.url==='/provider/openai/oauth/callback'){
  fs.mkdirSync(process.env.XDG_DATA_HOME+'/opencode',{recursive:true});
  fs.writeFileSync(process.env.XDG_DATA_HOME+'/opencode/auth.json',JSON.stringify({openai:{type:'oauth',access:'synthetic-private-native-token'}}));loggedIn=true;response=true;
 }else if(req.url==='/provider')response={connected:loggedIn?['openai']:[],all:[{id:'openai',models:{'account-model':{id:'account-model',name:'Account model'}}}]};
 else{res.writeHead(404);res.end();return;}
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(response));
}).listen(port,'127.0.0.1');`);
  assert.equal(result.code,0);assert.equal(result.events.find(event=>event.type==='account').provider,'openai');
  assert.equal(result.events.find(event=>event.type==='update').instructions,'Enter synthetic-device-code on the page');
  assert.ok(!result.stdout.includes('synthetic-private-native-token'));
});
