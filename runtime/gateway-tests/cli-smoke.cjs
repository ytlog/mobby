'use strict';
// Optional integration test against real CLIs, using only a local mock model.
// MOBBY_TEST_CODEX=/path/to/codex MOBBY_TEST_CLAUDE_JS=/path/to/cli.js node runtime/gateway-tests/cli-smoke.cjs
const {createServer}=require('node:http');
const {spawn}=require('node:child_process');
const {mkdtempSync,mkdirSync,writeFileSync,rmSync}=require('node:fs');
const {tmpdir}=require('node:os');
const {join,resolve}=require('node:path');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const bridge=resolve(__dirname,'../../runtime-android/src/main/assets/gateway/bridge.cjs');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
async function main() {
  const codex=process.env.MOBBY_TEST_CODEX, claude=process.env.MOBBY_TEST_CLAUDE_JS;
  assert.ok(codex && claude,'Set MOBBY_TEST_CODEX and MOBBY_TEST_CLAUDE_JS to the test CLI paths');
  const root=mkdtempSync(join(tmpdir(),'mobby-gateway-smoke-'));
  const work=join(root,'work');mkdirSync(work);
  const fixture=join(work,'fixture.txt');writeFileSync(fixture,'TOOL_ROUNDTRIP_OK\n');
  try {
    for (const mode of ['CODEX','CLAUDE']) for (const protocol of [mode==='CODEX'?'responses':'messages']) {
      let count=0, resultSeen=false, attachmentSeen=false;
      const attachment = JSON.stringify({name:'review.txt',text:'TEXT_ATTACHMENT_中文\n  `code` $(not-a-command)\n'});
      const prompt = `Read the test fixture.\n\n用户所选文本附件（JSON 数据，保留原文）：\n${attachment}`;
      function containsText(value, text) {
        if (typeof value === 'string') return value.includes(text);
        if (value && typeof value === 'object') return Object.values(value).some(v=>containsText(v,text));
        return false;
      }
      const server=createServer(async(req,res)=>{
        const expected = '/v1/' + protocol;
        // Native CLIs may probe models/capabilities: do not treat those as inference requests.
        if (req.method !== 'POST' || req.url.split('?')[0] !== expected) {
          res.writeHead(404, {'content-type':'application/json'});
          res.end(JSON.stringify({error:{message:'Mock capability endpoint unavailable'}}));
          return;
        }
        const chunks=[];for await(const c of req)chunks.push(c);
        const body=JSON.parse(Buffer.concat(chunks));count++;
        if (count===1) attachmentSeen=containsText(body,attachment);
        if(count>1) resultSeen=JSON.stringify(body).includes('TOOL_ROUNDTRIP_OK');
        const argumentsValue=mode==='CLAUDE'?{file_path:fixture}:{cmd:`cat '${fixture.replaceAll("'","'\\''")}'`,max_output_tokens:100};
        const canonical={content:count===1?'':'GATEWAY_TEST_OK',calls:count===1?[{id:'call_test',type:'function',function:{name:mode==='CLAUDE'?'Read':'exec_command',arguments:JSON.stringify(argumentsValue)}}]:[],input:10,output:4};
        const result=nativeResponse(canonical,protocol,'test-model');
        if(body.stream) sendNative(res,result,protocol,true);
        else {res.setHeader('content-type','application/json');res.end(JSON.stringify(result));}
      });
      server.listen(0,'127.0.0.1');await once(server,'listening');
      const home=mkdtempSync(join(root,'home-'));mkdirSync(join(home,'.codex'));
      // Fresh HOME and explicit minimal env: never load or forward the user's credentials.
      const env={PATH:process.env.PATH,HOME:home,CODEX_HOME:join(home,'.codex'),TMPDIR:tmpdir(),NO_COLOR:'1',TERM:'dumb',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',DISABLE_AUTOUPDATER:'1',
        MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${server.address().port}/v1`,protocol,model:'test-model',key:'fake-test-key'})};
      const exe=mode==='CODEX'?codex:process.execPath;
      const args=mode==='CODEX'?['exec','--skip-git-repo-check','--json','--',prompt]: [claude,'-p','--output-format','stream-json','--verbose','--',prompt];
      const child=spawn(process.execPath,[bridge,mode,exe,...args],{cwd:work,env,stdio:['ignore','pipe','pipe']});
      let output='';child.stdout.on('data',c=>output+=c);child.stderr.on('data',c=>output+=c);
      const timer=setTimeout(()=>child.kill('SIGTERM'),60000);
      let code;
      try { [code]=await once(child,'exit'); }
      finally {clearTimeout(timer);server.closeAllConnections();server.close();}
      assert.equal(code,0,output);
      assert.ok(output.includes('GATEWAY_TEST_OK'),output);
      assert.ok(resultSeen,`Tool result missing: ${mode}/${protocol}\n${output}`);
      assert.ok(attachmentSeen, `${mode}/${protocol}: attached UTF-8 text was altered or lost`);
      assert.equal(count,2);
      console.log(`PASS ${mode} -> ${protocol}: actual tool execution and result roundtrip`);
    }
  } finally {rmSync(root,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
