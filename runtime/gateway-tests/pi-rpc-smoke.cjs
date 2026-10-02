'use strict';
// Real locked Pi CLI, isolated HOME and synthetic Responses. Never uses user credentials.
// MOBBY_TEST_PI=/absolute/path/to/pi node runtime/gateway-tests/pi-rpc-smoke.cjs
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {spawn}=require('node:child_process');
const {createServer}=require('node:http');
const {once}=require('node:events');
const {randomUUID}=require('node:crypto');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const bridge=path.resolve(__dirname,'../../runtime/android/src/main/assets/gateway/bridge.cjs');

async function main() {
  const executable=process.env.MOBBY_TEST_PI;
  assert.ok(executable && path.isAbsolute(executable),'Set MOBBY_TEST_PI to the locked Pi executable');
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mobby-pi-smoke-'));
  const home=path.join(root,'home'),work=path.join(root,'work'),tmp=path.join(root,'tmp');
  for(const p of [home,work,tmp]) fs.mkdirSync(p);
  const piHome=path.join(home,'.pi','agent');fs.mkdirSync(piHome,{recursive:true});
  const original='{"sentinel":"USER_CONFIG_UNCHANGED"}';
  fs.writeFileSync(path.join(piHome,'models.json'),original);
  const skill=path.join(piHome,'skills','fixture-skill');fs.mkdirSync(skill,{recursive:true});
  fs.writeFileSync(path.join(skill,'SKILL.md'),'---\nname: fixture-skill\ndescription: PI_SKILL_DISCOVERY_OK\n---\nRead fixture.txt.\n');
  const projectSkill=path.join(work,'.pi','skills','project-fixture');fs.mkdirSync(projectSkill,{recursive:true});
  fs.writeFileSync(path.join(projectSkill,'SKILL.md'),'---\nname: project-fixture\ndescription: PI_PROJECT_SKILL_OK\n---\nRead result.txt.\n');
  fs.writeFileSync(path.join(work,'fixture.txt'),'PI_READ_OK');
  let requests=[],step=0,mode='tools',startSteer,releaseSteer,startCancel,closeCancel;
  const steerStarted=new Promise(resolve=>startSteer=resolve),steerGate=new Promise(resolve=>releaseSteer=resolve);
  const cancelStarted=new Promise(resolve=>startCancel=resolve),cancelClosed=new Promise(resolve=>closeCancel=resolve);
  const server=createServer(async(req,res)=>{
    if(req.method!=='POST'||req.url!=='/v1/responses') {res.writeHead(404);res.end();return;}
    const chunks=[];for await(const c of req)chunks.push(c);
    const body=JSON.parse(Buffer.concat(chunks));requests.push(body);
    assert.equal(req.headers.authorization,'Bearer fake-pi-key');
    if(mode==='error'){res.writeHead(401,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'FAKE_AUTH_REJECTED'}}));return;}
    if(mode==='cancel') {
      res.writeHead(200,{'content-type':'text/event-stream'});res.write(': waiting\n\n');
      res.on('close',closeCancel);startCancel();return;
    }
    if(mode==='steer' && !JSON.stringify(body).includes('STEER_INSERTED')) {startSteer();await steerGate;}
    const tools=[['read',{path:'fixture.txt'}],['write',{path:'result.txt',content:'PI_WRITE_OK'}],
      ['edit',{path:'result.txt',oldText:'PI_WRITE_OK',newText:'PI_EDIT_OK'}],
      ['bash',{command:'cat fixture.txt result.txt'}]];
    const item=mode==='tools'?tools[step++]:null;
    const calls=item?[{id:'call_'+step,type:'function',function:{name:item[0],arguments:JSON.stringify(item[1])}}]:[];
    sendNative(res,nativeResponse({content:calls.length?'':'PI_REPLY_中文\u2028OK',calls,input:12,output:6},'responses','test-model'),'responses',Boolean(body.stream));
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const children=[];
  function launch(session,resume=false) {
    const env={PATH:process.env.PATH,HOME:home,TMPDIR:tmp,TERM:'dumb',NO_COLOR:'1',
      MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${server.address().port}/v1`,protocol:'responses',model:'test-model',key:'fake-pi-key'})};
    const child=spawn(process.execPath,[bridge,'PI',executable,'--mode','rpc','--provider','mobby','--model','test-model','--thinking','off',resume?'--session':'--session-id',session],{cwd:work,env});
    children.push(child);
    let partial='',stderr='',events=[];const waiters=new Set();
    child.stderr.on('data',c=>stderr+=c);
    child.stdout.on('data',c=>{
      partial+=c;
      for(let end;(end=partial.indexOf('\n'))>=0;) {
        const line=partial.slice(0,end);partial=partial.slice(end+1);
        if(line) events.push(JSON.parse(line));
      }
      for(const waiter of waiters)waiter();
    });
    const exited=once(child,'exit');
    async function wait(predicate) {
      const find=()=>events.find(predicate);
      if(find())return find();
      return new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>{waiters.delete(waiter);reject(Error('Pi event timeout: '+stderr+' '+JSON.stringify(events).slice(-2500)));},20000);
        const waiter=()=>{const result=find();if(result){clearTimeout(timer);waiters.delete(waiter);resolve(result);}};
        waiters.add(waiter);
      });
    }
    const send=v=>child.stdin.write(JSON.stringify(v)+'\n');
    async function turn(message,images) {
      events=[];
      send({id:'prompt-'+randomUUID(),type:'prompt',message,...(images?{images}: {})});
      await wait(e=>e.type==='agent_settled');
      return [...events];
    }
    return {child,send,wait,turn,exited,events:()=>events,stderr:()=>stderr};
  }
  try {
    const id=randomUUID(),live=launch(id);
    live.send({id:'state',type:'get_state'});
    const state=await live.wait(e=>e.type==='response'&&e.command==='get_state');
    assert.equal(state.data.sessionId,id);
    live.send({id:'commands',type:'get_commands'});
    const commands=(await live.wait(e=>e.command==='get_commands')).data.commands;
    assert.ok(commands.some(c=>c.name==='skill:fixture-skill'));
    assert.ok(commands.some(c=>c.name==='skill:project-fixture'));
    const image={type:'image',mimeType:'image/png',data:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4wAAAABJRU5ErkJggg=='};
    const events=await live.turn('附件原文：中文\u2028`literal` $(not-a-command)',[image]);
    assert.equal(events.filter(e=>e.type==='message_end'&&e.message.role==='assistant').at(-1).message.stopReason,'stop');
    assert.deepEqual(events.filter(e=>e.type==='tool_execution_end').map(e=>e.isError),[false,false,false,false]);
    assert.equal(fs.readFileSync(path.join(work,'result.txt'),'utf8'),'PI_EDIT_OK');
    assert.equal(requests.length,5);
    assert.ok(JSON.stringify(requests[0]).includes(image.data),'Image bytes must reach Responses unchanged');
    assert.ok(JSON.stringify(requests[0]).includes('$(not-a-command)'),'Prompt must be literal');
    assert.ok(JSON.stringify(requests.at(-1)).includes('PI_READ_OKPI_EDIT_OK'),'Actual bash result must roundtrip');
    mode='text';await live.turn('SECOND_TURN');
    assert.ok(JSON.stringify(requests.at(-1)).includes('PI_READ_OK'),'Live turn must retain context');
    live.send({id:'fresh-session',type:'new_session'});
    const fresh=(await live.wait(e=>e.type==='response'&&e.id==='fresh-session')).data;
    assert.equal(fresh.cancelled,false);
    live.send({id:'fresh-state',type:'get_state'});
    const freshId=(await live.wait(e=>e.type==='response'&&e.id==='fresh-state')).data.sessionId;
    assert.notEqual(freshId,id,'New session must have a distinct identity');
    await live.turn('FRESH_TURN');
    assert.ok(!JSON.stringify(requests.at(-1)).includes('SECOND_TURN'),'New session must not inherit the prior transcript');
    mode='steer';const steered=live.turn('STEER_TURN');
    await steerStarted;live.send({id:'steer',type:'steer',message:'STEER_INSERTED'});
    assert.equal((await live.wait(e=>e.command==='steer')).success,true);releaseSteer();await steered;
    assert.ok(JSON.stringify(requests.at(-1)).includes('STEER_INSERTED'),'Inserted instruction must actually reach the next model step');
    mode='text';
    live.child.stdin.end();assert.equal((await live.exited)[0],0,live.stderr());
    const resumed=launch(id,true);resumed.send({id:'state',type:'get_state'});
    assert.equal((await resumed.wait(e=>e.command==='get_state')).data.sessionId,id);
    await resumed.turn('COLD_RESUME');
    assert.ok(JSON.stringify(requests.at(-1)).includes('SECOND_TURN'),'Cold resume must retain context');
    mode='error';const errors=await resumed.turn('ERROR');
    assert.equal(errors.filter(e=>e.type==='message_end'&&e.message.role==='assistant').at(-1).message.stopReason,'error');
    mode='cancel';resumed.send({id:'cancel-prompt',type:'prompt',message:'CANCEL'});
    await cancelStarted;
    resumed.child.kill('SIGTERM');await resumed.exited;await cancelClosed;
    assert.equal(fs.readFileSync(path.join(piHome,'models.json'),'utf8'),original);
    assert.deepEqual(fs.readdirSync(tmp).filter(n=>n.startsWith('mobby-pi-')),[],'Temporary bridge config must be removed');
    console.log('PASS Pi RPC: images, UTF-8, skills, tools, live turns, fresh session, steering, cold resume, errors, cancellation and config isolation');
  } finally {
    for(const child of children)if(child.exitCode===null && child.signalCode===null)child.kill('SIGTERM');
    server.closeAllConnections();server.close();fs.rmSync(root,{recursive:true,force:true});
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
