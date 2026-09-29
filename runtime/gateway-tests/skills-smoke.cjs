'use strict';
// Real CLI skill discovery + tool read against a local mock gateway. No user credentials or HOME.
const {createServer} = require('node:http');
const {spawn} = require('node:child_process');
const {mkdtempSync,mkdirSync,writeFileSync,rmSync} = require('node:fs');
const {tmpdir} = require('node:os');
const {join,resolve} = require('node:path');
const {once} = require('node:events');
const assert = require('node:assert/strict');
const bridge = resolve(__dirname,'../../runtime/android/src/main/assets/gateway/bridge.cjs');
const {nativeResponse,sendNative} = require('./native-fixture.cjs');
async function main() {
  const codex=process.env.MOBBY_TEST_CODEX, claude=process.env.MOBBY_TEST_CLAUDE_JS;
  assert.ok(codex && claude, 'Set test CLI paths');
  const root=mkdtempSync(join(tmpdir(),'mobby-skill-smoke-'));
  try {
    for (const mode of ['CODEX','CLAUDE']) {
      const home=join(root,mode), work=join(home,'workspace');mkdirSync(work,{recursive:true});mkdirSync(join(home,'.codex'),{recursive:true});
      const folder=join(home,mode==='CODEX'?'.agents':'.claude','skills','skill-test');mkdirSync(folder,{recursive:true});
      const file=join(folder,'SKILL.md');
      writeFileSync(file,'---\nname: skill-test\ndescription: SKILL_DISCOVERY_SENTINEL use only for fixture validation\n---\n\nSKILL_BODY_SENTINEL: read this workflow before responding.\n');
      let count=0, discovered=false, read=false;
      const protocol=mode==='CODEX'?'responses':'messages';
      const server=createServer(async(req,res)=>{
        if (req.method!=='POST' || req.url.split('?')[0]!==`/v1/${protocol}`) {res.writeHead(404);res.end();return;}
        const chunks=[];for await(const chunk of req)chunks.push(chunk);
        const body=JSON.parse(Buffer.concat(chunks));const serialized=JSON.stringify(body);count++;
        if (count===1) discovered=serialized.includes('SKILL_DISCOVERY_SENTINEL');
        if (count>1) read=serialized.includes('SKILL_BODY_SENTINEL');
        const args=mode==='CODEX'?{cmd:`cat '${file.replaceAll("'","'\\''")}'`,max_output_tokens:300}:{skill:'skill-test'};
        const canonical={content:count===1?'':'SKILL_TEST_OK\n\n````SKILL.md\n---\nname: proposed-skill\ndescription: Generated proposal fixture\n---\nReview the requested files.\n````',calls:count===1?[{id:'call_skill',type:'function',function:{name:mode==='CODEX'?'exec_command':'Skill',arguments:JSON.stringify(args)}}]:[],input:10,output:4};
        const result=nativeResponse(canonical,protocol,'test-model');
        if(body.stream)sendNative(res,result,protocol,true);else {res.setHeader('content-type','application/json');res.end(JSON.stringify(result));}
      });
      server.listen(0,'127.0.0.1');await once(server,'listening');
      const env={PATH:process.env.PATH,HOME:home,CODEX_HOME:join(home,'.codex'),TMPDIR:tmpdir(),NO_COLOR:'1',TERM:'dumb',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',DISABLE_AUTOUPDATER:'1',MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${server.address().port}/v1`,protocol,model:'test-model',key:'fake-test-key'})};
      const invocation=mode==='CODEX'?'$skill-test':'/skill-test';
      const instruction=mode==='CLAUDE'?'请使用 Skill 工具调用下列已选择的技能，遵守 Agent 权限检查：':'请使用下列已选择的技能，读取对应 SKILL.md 并遵守 Agent 权限检查：';
      const prompt=`${instruction}\n${invocation} — ${file}\n\nRead the selected skill and complete the validation.`;
      const args=mode==='CODEX'?[codex,'exec','--skip-git-repo-check','--json','--',prompt]:[process.execPath,claude,'-p','--output-format','stream-json','--verbose','--',prompt];
      const child=spawn(process.execPath,[bridge,mode,...args],{cwd:work,env,stdio:['ignore','pipe','pipe']});
      let output='';child.stdout.on('data',c=>output+=c);child.stderr.on('data',c=>output+=c);
      const timeout=setTimeout(()=>child.kill('SIGTERM'),60000);
      let code;try { [code]=await once(child,'exit'); } finally {clearTimeout(timeout);server.closeAllConnections();server.close();}
      assert.equal(code,0,output);assert.ok(output.includes('SKILL_TEST_OK'),output);assert.ok(output.includes('proposed-skill'),output);
      assert.ok(discovered,`${mode}: skill missing from actual CLI discovery`);
      assert.ok(read,`${mode}: skill file was not returned by actual tool\n${output}`);
      console.log(`PASS ${mode}: skill discovered and read through actual CLI tool`);
    }
  } finally { rmSync(root,{recursive:true,force:true}); }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
