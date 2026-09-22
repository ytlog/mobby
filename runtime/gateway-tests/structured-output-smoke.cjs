'use strict';
// Pinned Android CLI structured-output contract: new + resumed sessions, isolated HOME,
// synthetic native model responses and fake credentials. No production gateway is used.
const {createServer} = require('node:http');
const {execFile} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const {once} = require('node:events');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const assert = require('node:assert/strict');
const {isDeepStrictEqual} = require('node:util');
const {nativeResponse, sendNative} = require('./native-fixture.cjs');
const adb = process.env.MOBBY_TEST_ADB;
assert.ok(adb, 'Set MOBBY_TEST_ADB to the connected Android device adb');
const quote = value => "'" + String(value).replaceAll("'", "'\\''") + "'";
const command = args => new Promise(resolve => execFile(adb, args, {timeout:45000,maxBuffer:2*1024*1024}, (error,stdout,stderr) => resolve({code:error?.code??0,stdout,stderr})).stdin.end());
const remote = args => command(['shell', ['run-as','com.github.ytlog.mobby.android',...args].map(quote).join(' ')]);
async function checked(args) { const r=await remote(args); assert.equal(r.code,0,r.stderr); return r.stdout.trim(); }
const contract=readFileSync(resolve(__dirname,'../../runtime-engine/src/main/kotlin/com/mobby/runtime/engine/SkillGeneration.kt'),'utf8');
const schema=JSON.parse(contract.match(/val schema = """([^]*?)"""/)[1]);
const answer={kind:'proposal',message:'Proposal ready',name:'structured-fixture',description:'Synthetic contract check',body:'Review the supplied diff.'};
async function main() {
  const home=await checked(['pwd']), prefix=home+'/files/libtermux/usr';
  const native=(await checked(['readlink',prefix+'/bin/codex'])).replace(/\/[^/]+$/,'');
  const root=home+'/files/structured-smoke-'+randomBytes(6).toString('hex');
  const pidFile=root+'/process-group';
  let port, mode, count=0, schemaSeen=false, structuredTool=false, structuredSent=false;
  const server=createServer(async(req,res)=>{
    const protocol=mode==='CODEX'?'responses':'messages';
    if(req.method!=='POST'||req.url.split('?')[0]!==`/v1/${protocol}`){res.writeHead(404);res.end();return;}
    const chunks=[];for await(const c of req)chunks.push(c);
    const body=JSON.parse(Buffer.concat(chunks));count++;
    if(count>8){res.writeHead(500);res.end();return;}
    let calls=[];
    if(mode==='CODEX') schemaSeen ||= isDeepStrictEqual(body.text?.format?.schema,schema);
    else {
      const tool=(body.tools||[]).find(t=>t.name==='StructuredOutput');
      schemaSeen ||= tool && isDeepStrictEqual(tool.input_schema,schema);
      structuredTool ||= !!tool;
      if(tool && !structuredSent) { calls=[{id:'structured-'+count,function:{name:tool.name,arguments:JSON.stringify(answer)}}]; structuredSent=true; }
      else schemaSeen ||= isDeepStrictEqual(body.output_config?.format?.schema,schema);
    }
    sendNative(res,nativeResponse({content:calls.length?'':JSON.stringify(answer),calls,input:10,output:10},protocol,'test-model'),protocol,Boolean(body.stream));
  });
  try {
    await checked(['mkdir','-p',root]);
    server.listen(0,'127.0.0.1');await once(server,'listening');
    const reverse=await command(['reverse','tcp:0','tcp:'+server.address().port]);assert.equal(reverse.code,0);port=reverse.stdout.trim();assert.match(port,/^\d+$/);
    for(mode of ['CODEX','CLAUDE']) {
      const cliHome=root+'/'+mode, work=cliHome+'/workspace';
      await checked(['mkdir','-p',cliHome+'/.codex',work,cliHome+'/tmp']);
      const env=['env','-i','PREFIX='+prefix,'HOME='+cliHome,'CODEX_HOME='+cliHome+'/.codex','TMPDIR='+cliHome+'/tmp','PATH='+prefix+'/bin:/system/bin','LD_LIBRARY_PATH='+prefix+'/lib:'+native,'SHELL='+prefix+'/bin/bash','NO_COLOR=1','DISABLE_AUTOUPDATER=1','CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1','GIT_CONFIG_NOSYSTEM=1','GIT_TEMPLATE_DIR='+prefix+'/share/git-core/templates'];
      await checked([...env,prefix+'/bin/git','init','-q',work]);
      const schemaPath=cliHome+'/schema.json';
      await checked([...env,prefix+'/bin/node','-e',`require('fs').writeFileSync(${JSON.stringify(schemaPath)},${JSON.stringify(JSON.stringify(schema))})`]);
      const inputPath=cliHome+'/input.jsonl';
      const input=JSON.stringify({type:'user',message:{role:'user',content:[{type:'text',text:'Return the requested structured skill proposal.'}]}})+'\n';
      if(mode==='CLAUDE')await checked([...env,prefix+'/bin/node','-e',`require('fs').writeFileSync(${JSON.stringify(inputPath)},${JSON.stringify(input)})`]);
      let session;
      for(const resume of [false,true]) {
        count=0;schemaSeen=false;structuredTool=false;structuredSent=false;
        if(resume)assert.match(session||'',/^[a-zA-Z0-9-]+$/,'Missing native session id');
        const config=JSON.stringify({endpoint:`http://127.0.0.1:${port}/v1`,protocol:mode==='CODEX'?'responses':'messages',model:'test-model',key:'fake-structured-key'});
        const args=mode==='CODEX'?['--sandbox','danger-full-access','-c','approval_policy="never"','exec','--json',...(resume?['resume',session]:[]),'--output-schema',schemaPath,'--','Return the requested structured skill proposal.']:
          ['-p','--input-format','stream-json','--output-format','stream-json','--verbose','--permission-prompt-tool','stdio','--json-schema',JSON.stringify(schema),...(resume?['--resume',session]:[])];
        const launch=[...env,...(mode==='CLAUDE'?['MOBBY_AGENT_INPUT_FILE='+inputPath]:[]),'MOBBY_GATEWAY_CONFIG='+config,'timeout','-s','TERM','35',prefix+'/bin/node',home+'/files/gateway.cjs',mode,prefix+'/bin/'+(mode==='CODEX'?'codex':'claude'),...args];
        const r=await remote(['/system/bin/toybox','setsid','-w','/system/bin/sh','-c','echo $$ > '+quote(pidFile)+'; cd '+quote(work)+' && exec '+launch.map(quote).join(' ')]);
        const pid=(await remote(['cat',pidFile])).stdout.trim();if(/^[1-9][0-9]*$/.test(pid))await remote(['/system/bin/toybox','kill','-KILL','--','-'+pid]);
        await remote(['rm','-f',pidFile]);
        const events=r.stdout.split('\n').flatMap(line=>{try{return[JSON.parse(line)];}catch{return[];}});
        const terminal=events.find(e=>e.type===(mode==='CODEX'?'turn.completed':'result'));
        const value=mode==='CODEX'?events.filter(e=>e.type==='item.completed'&&e.item?.type==='agent_message').map(e=>{try{return JSON.parse(e.item.text);}catch{return null;}}).find(v=>v?.kind==='proposal'):terminal?.structured_output;
        session=mode==='CODEX'?events.find(e=>e.type==='thread.started')?.thread_id:terminal?.session_id;
        console.log(`${mode}/${resume?'resume':'new'} exit=${r.code} requests=${count} schema=${!!schemaSeen} structuredTool=${structuredTool} result=${!!value}`);
        assert.equal(r.code,0,r.stderr);assert.ok(schemaSeen,'Native request did not carry schema');assert.ok(terminal,'No terminal evidence');
        if(mode==='CLAUDE')assert.ok(terminal.subtype==='success'&&!terminal.is_error,'Claude did not succeed');
        assert.deepEqual(value,answer,'Structured result differs from model response');
      }
    }
  } finally {
    const pid=(await remote(['cat',pidFile])).stdout.trim();if(/^[1-9][0-9]*$/.test(pid))await remote(['/system/bin/toybox','kill','-KILL','--','-'+pid]);
    server.closeAllConnections();server.close();if(port)await command(['reverse','--remove','tcp:'+port]);
    assert.equal((await remote(['rm','-rf',root])).code,0,'Isolated fixture cleanup failed');
  }
}
main().catch(error=>{console.error(error.message);process.exitCode=1;});
