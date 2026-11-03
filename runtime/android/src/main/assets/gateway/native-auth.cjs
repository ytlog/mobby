'use strict';
// Uses the bundled agents' own authentication implementations. stdout is a
// small, allowlisted UI protocol; raw CLI diagnostics and credentials stay local.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const readline = require('node:readline');
const {spawn} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const {randomBytes} = require('node:crypto');
const {createNativeProxy, cleanNativeEnvironment} = require('./native-network.cjs');
const emit = event => process.stdout.write(JSON.stringify(event) + '\n');
const input = readline.createInterface({input:process.stdin, crlfDelay:Infinity});
let pendingInput;
input.on('line', line => { if (pendingInput) { const done=pendingInput; pendingInput=null; done(line); } });
const answer = () => new Promise(resolve => { pendingInput=resolve; });
const children = new Set();
function child(executable, args, env) {
  const process = spawn(executable, args, {env, stdio:['pipe','pipe','pipe']});
  children.add(process); process.once('exit', () => children.delete(process));
  process.stderr.resume();
  return process;
}
async function completed(process) {
  return new Promise((resolve,reject) => { process.once('error',reject); process.once('exit', code => code===0 ? resolve() : reject(Error('native_cli_failed'))); });
}
function rpc(process) {
  let next=0; const requests=new Map(); const events=[]; const listeners=new Set();
  readline.createInterface({input:process.stdout}).on('line', line => {
    let value; try { value=JSON.parse(line); } catch (_) { return; }
    if (value.id !== undefined) {
      const request=requests.get(value.id); if (!request) return;
      requests.delete(value.id); value.error ? request.reject(Error('native_rpc_failed')) : request.resolve(value.result);
    } else { events.push(value); for (const listener of listeners) listener(value); }
  });
  process.once('exit', () => { for (const request of requests.values()) request.reject(Error('native_cli_exited')); });
  return {
    call(method, params) { const id=++next; return new Promise((resolve,reject) => {
      requests.set(id,{resolve,reject}); process.stdin.write(JSON.stringify({id,method,params})+'\n');
    }); },
    notify(method,params) { process.stdin.write(JSON.stringify({method,params})+'\n'); },
    event(method) { const existing=events.find(event=>event.method===method); return existing ? Promise.resolve(existing.params) :
      new Promise(resolve=> { const listener=event=> { if(event.method===method) { listeners.delete(listener); resolve(event.params); } }; listeners.add(listener); }); }
  };
}
async function codex(operation, method, executable, env) {
  if (operation==='methods') { emit({type:'methods',methods:[{id:'chatgpt',label:'ChatGPT'},{id:'chatgptDeviceCode',label:'ChatGPT · Device code'}]}); return; }
  const process=child(executable,['-c','model_provider="openai"','-c','cli_auth_credentials_store="file"','app-server','--listen','stdio://'],env);
  const client=rpc(process);
  try {
    await client.call('initialize',{clientInfo:{name:'mobby',version:'1.0'}}); client.notify('initialized',{});
    if(operation==='login') {
      if(!['chatgpt','chatgptDeviceCode'].includes(method)) throw Error('unsupported_login_method');
      const login=await client.call('account/login/start',{type:method});
      emit({type:'update',url:login.authUrl || login.verificationUrl,code:login.userCode});
      const result=await client.event('account/login/completed');
      if(!result.success) throw Error('native_login_failed');
    }
    const status=await client.call('account/read',{refreshToken:true});
    if(!status.account || status.account.type!=='chatgpt') throw Error('native_account_missing');
    let cursor=null; const models=[];
    do {
      const page=await client.call('model/list',{limit:100,cursor,includeHidden:false});
      for(const model of page.data || []) models.push({id:model.model || model.id,name:model.displayName || model.model || model.id});
      cursor=page.nextCursor;
      if(models.length>2000) throw Error('native_catalog_too_large');
    } while(cursor);
    emit({type:'account',provider:'openai',label:'ChatGPT',models});
  } finally {process.kill('SIGTERM');}
}
async function pi(operation, method, env) {
  const root=path.join(env.PREFIX,'lib/node_modules/@earendil-works/pi-coding-agent');
  const {ModelRuntime}=await import(pathToFileURL(path.join(root,'dist/core/model-runtime.js')));
  const runtime=await ModelRuntime.create({authPath:path.join(env.PI_CODING_AGENT_DIR,'auth.json'),modelsPath:null,refreshOnCreate:false});
  const compatible=model=>['openai-responses','openai-codex-responses'].includes(model.api);
  const methods=runtime.getProviders().filter(provider=>provider.auth.oauth && provider.getModels().some(compatible));
  if(operation==='methods') {emit({type:'methods',methods:methods.map(provider=>({id:provider.id,label:provider.auth.oauth.name}))});return;}
  if(!methods.some(provider=>provider.id===method)) throw Error('unsupported_login_method');
  if(operation==='login') await runtime.login(method,'oauth',{
    prompt: async prompt=>{
      emit({type:'update',prompt:prompt.message,options:prompt.options});
      if(prompt.signal?.aborted) return '';
      return new Promise((resolve,reject)=>{
        const abort=()=>{pendingInput=null;resolve('');};
        prompt.signal?.addEventListener('abort',abort,{once:true});
        answer().then(value=>{prompt.signal?.removeEventListener('abort',abort);resolve(value);},reject);
      });
    },
    notify:event=>{
      if(event.type==='auth_url') emit({type:'update',url:event.url,instructions:event.instructions});
      if(event.type==='info' || event.type==='progress') emit({type:'update',instructions:event.message});
      if(event.type==='device_code') emit({type:'update',url:event.verificationUri,code:event.userCode});
    }
  });
  const auth=await runtime.checkAuth(method);
  if(auth?.type!=='oauth') throw Error('native_account_missing');
  const models=(await runtime.getAvailable(method)).filter(compatible).map(model=>({id:model.id,name:model.name || model.id}));
  emit({type:'account',provider:method,label:methods.find(provider=>provider.id===method).auth.oauth.name,models});
}
async function claude(operation, method, executable, env) {
  if(operation==='methods') {emit({type:'methods',methods:[{id:'claudeai',label:'Claude'}]});return;}
  if(method!=='claudeai') throw Error('unsupported_login_method');
  if(operation==='login') {
    // Claude's own browser opener executes this helper; authorization remains in
    // Claude and the system browser. No password/code is interpreted by mobby.
    const browserToken=randomBytes(32).toString('hex');
    const browser=http.createServer(async(req,res)=>{
      if(req.headers.authorization!==`Bearer ${browserToken}`) {res.writeHead(401);res.end();return;}
      let body='';for await(const chunk of req) {body+=chunk;if(body.length>16384){req.destroy();return;}}
      try {const url=new URL(JSON.parse(body).url);if(url.protocol!=='https:') throw Error();emit({type:'update',url:url.toString()});res.end('OK');}
      catch(_){res.writeHead(400);res.end();}
    });
    await new Promise(resolve=>browser.listen(0,'127.0.0.1',resolve));
    const helper=path.join(env.CLAUDE_CONFIG_DIR,'browser');
    fs.writeFileSync(helper,`#!/system/bin/sh\nexec "${env.PREFIX}/bin/node" "${__filename}" browser "$@"\n`,{mode:0o700});
    try {
      const process=child(executable,['auth','login','--claudeai'],{...env,BROWSER:helper,
        MOBBY_BROWSER_PORT:String(browser.address().port),MOBBY_BROWSER_TOKEN:browserToken});
      readline.createInterface({input:process.stdout}).on('line', line => {
        const match=line.match(/https:\/\/[^\s<>]+/);
        if(match) {
          try { const url=new URL(match[0]); if(['claude.ai','claude.com','console.anthropic.com','platform.claude.com','auth.anthropic.com'].includes(url.hostname)) emit({type:'update',url:url.toString()}); } catch(_) {}
        }
      });
      await completed(process);
    } finally {browser.closeAllConnections();browser.close();fs.rmSync(helper,{force:true});}
  }
  const process=child(executable,['auth','status','--json'],env); let result='';
  process.stdout.on('data',chunk=>{result+=chunk;if(result.length>65536)process.kill('SIGTERM');});
  await completed(process);
  if(!JSON.parse(result).loggedIn) throw Error('native_account_missing');
  emit({type:'account',provider:'anthropic',label:'Claude',models:['sonnet','opus','haiku'].map(id=>({id,name:id}))});
}
async function openCode(operation, method, executable, env) {
  const password=randomBytes(32).toString('hex');
  const reservation=http.createServer(); await new Promise(resolve=>reservation.listen(0,'127.0.0.1',resolve));
  const port=reservation.address().port;await new Promise(resolve=>reservation.close(resolve));
  const process=child(executable,['serve','--hostname','127.0.0.1','--port',String(port)],
    {...env,OPENCODE_SERVER_PASSWORD:password,OPENCODE_DISABLE_AUTOUPDATE:'1',OPENCODE_CONFIG_CONTENT:JSON.stringify({enabled_providers:['openai']})});
  process.stdout.resume();
  const headers={authorization:'Basic '+Buffer.from('opencode:'+password).toString('base64'),'content-type':'application/json'};
  async function request(route,body) {
    const response=await fetch(`http://127.0.0.1:${port}${route}`,{headers,method:body===undefined?'GET':'POST',body:body===undefined?undefined:JSON.stringify(body)});
    if(!response.ok) {await response.body?.cancel();throw Error('native_auth_server_failed');}
    return response.json();
  }
  try {
    let methods;
    for(let attempt=0;attempt<100;attempt++) {
      if(process.exitCode!==null) throw Error('native_cli_exited');
      try {methods=await request('/provider/auth');break;} catch(_) {await new Promise(resolve=>setTimeout(resolve,100));}
    }
    if(!methods) throw Error('native_cli_not_ready');
    const available=(methods.openai || []).map((value,index)=>({...value,index})).filter(value=>value.type==='oauth');
    if(operation==='methods') {emit({type:'methods',methods:available.map(value=>({id:String(value.index),label:value.label}))});return;}
    const selected=available.find(value=>String(value.index)===method);
    if(!selected) throw Error('unsupported_login_method');
    if(operation==='login') {
      const inputs={};
      for(const prompt of selected.prompts || []) {
        emit({type:'update',prompt:prompt.message,options:prompt.options?.map(value=>({id:value.value,label:value.label}))});
        inputs[prompt.key]=await answer();
      }
      const auth=await request('/provider/openai/oauth/authorize',{method:selected.index,inputs});
      emit({type:'update',url:auth.url,instructions:auth.instructions,prompt:auth.method==='code'?auth.instructions:undefined});
      const code=auth.method==='code'?await answer():undefined;
      await request('/provider/openai/oauth/callback',{method:selected.index,code});
    }
    const catalog=await request('/provider');
    if(!catalog.connected?.includes('openai')) throw Error('native_account_missing');
    const provider=catalog.all?.find(value=>value.id==='openai');
    const models=Object.values(provider?.models || {}).map(model=>({id:model.id,name:model.name || model.id}));
    const credentials=JSON.parse(fs.readFileSync(path.join(env.XDG_DATA_HOME,'opencode/auth.json'),'utf8'));
    if(credentials.openai?.type!=='oauth') throw Error('native_account_missing');
    emit({type:'account',provider:'openai',label:'ChatGPT',models});
  } finally {process.kill('SIGTERM');}
}
async function main() {
  const [operation,mode,method,executable]=process.argv.slice(2);
  if(operation==='browser') {
    const url=process.argv.slice(3).find(value=>value.startsWith('https://'));
    if(!url) throw Error('missing_auth_url');
    await fetch(`http://127.0.0.1:${process.env.MOBBY_BROWSER_PORT}`,{method:'POST',headers:{authorization:`Bearer ${process.env.MOBBY_BROWSER_TOKEN}`},body:JSON.stringify({url})});
    input.close();return;
  }
  const proxy=await createNativeProxy();
  const env={...cleanNativeEnvironment(process.env),HTTPS_PROXY:proxy.url,https_proxy:proxy.url,HTTP_PROXY:proxy.url,http_proxy:proxy.url,
    NO_PROXY:'127.0.0.1,localhost,::1',no_proxy:'127.0.0.1,localhost,::1'};
  // All native subprocesses use the private materialized credential directories.
  const stop=()=>{for(const process of children)process.kill('SIGTERM');proxy.close();input.close();};
  process.on('SIGTERM',()=>{stop();process.exit(143);});process.on('SIGINT',()=>{stop();process.exit(130);});
  try {
    if(mode==='CODEX') await codex(operation,method,executable,env);
    else if(mode==='PI') await pi(operation,method,env);
    else if(mode==='CLAUDE') await claude(operation,method,executable,env);
    else if(mode==='OPEN_CODE') await openCode(operation,method,executable,env);
    else throw Error('unsupported_native_agent');
  } finally {stop();}
}
if(require.main===module)main().catch(()=>{emit({type:'failed'});process.exitCode=1;});
