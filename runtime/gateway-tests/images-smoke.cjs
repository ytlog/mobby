'use strict';
// Actual CLI image input against an isolated mock gateway; no real model or user media.
// MOBBY_TEST_CODEX=/path/to/codex MOBBY_TEST_CLAUDE_JS=/path/to/cli.js node runtime/gateway-tests/images-smoke.cjs
const {createServer}=require('node:http');
const {spawn}=require('node:child_process');
const {mkdtempSync,mkdirSync,writeFileSync,rmSync}=require('node:fs');
const {tmpdir}=require('node:os');
const {join,resolve}=require('node:path');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const bridge=resolve(__dirname,'../../runtime-android/src/main/assets/gateway/bridge.cjs');
const {nativeResponse,sendNative}=require(bridge);
async function main() {
  const codex=process.env.MOBBY_TEST_CODEX, claude=process.env.MOBBY_TEST_CLAUDE_JS;
  assert.ok(codex && claude,'Set MOBBY_TEST_CODEX and MOBBY_TEST_CLAUDE_JS to the test CLI paths');
  const root=mkdtempSync(join(tmpdir(),'mobby-gateway-smoke-'));
  const work=join(root,'work');mkdirSync(work);
  const fixture=join(work,'fixture.png');
  const imageData='iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII=';
  writeFileSync(fixture,Buffer.from(imageData,'base64'));
  try {
    for (const mode of ['CODEX','CLAUDE']) for (const protocol of ['chat','messages','responses']) {
      if (process.env.NATIVE_ONLY && protocol !== (mode==='CODEX'?'responses':'messages')) continue;
      let count=0, imageSeen=false;
      const prompt='Describe the attached test image.';
      function images(value) {
        if (!value || typeof value!=='object') return [];
        if (['image','image_url','input_image'].includes(value.type)) return [value];
        return Object.values(value).flatMap(images);
      }
      const server=createServer(async(req,res)=>{
        const expected = protocol === 'chat' ? '/v1/chat/completions' : '/v1/' + protocol;
        // Native CLIs may probe models/capabilities: do not treat those as inference requests.
        if (req.method !== 'POST' || req.url.split('?')[0] !== expected) {
          res.writeHead(404, {'content-type':'application/json'});
          res.end(JSON.stringify({error:{message:'Mock capability endpoint unavailable'}}));
          return;
        }
        const chunks=[];for await(const c of req)chunks.push(c);
        const body=JSON.parse(Buffer.concat(chunks));count++;
        const blocks=images(body);
        if(process.env.IMAGE_METADATA) console.log(blocks.map(b=>({type:b.type,detail:b.detail,source:b.source?.type})));
        imageSeen=blocks.some(block=> (block.image_url?.url || block.image_url || block.source?.data || '').includes(imageData));
        const canonical={content:'IMAGE_INPUT_OK',calls:[],input:10,output:4};
        const result=protocol==='chat'?{choices:[{message:{role:'assistant',content:canonical.content,tool_calls:canonical.calls},finish_reason:canonical.calls.length?'tool_calls':'stop'}],usage:{prompt_tokens:10,completion_tokens:4}}:nativeResponse(canonical,protocol,'test-model');
        if(body.stream) sendNative(res,result,protocol,true);
        else {res.setHeader('content-type','application/json');res.end(JSON.stringify(result));}
      });
      server.listen(0,'127.0.0.1');await once(server,'listening');
      const home=mkdtempSync(join(root,'home-'));mkdirSync(join(home,'.codex'));
      // Fresh HOME and explicit minimal env: never load or forward the user's credentials.
      const env={PATH:process.env.PATH,HOME:home,CODEX_HOME:join(home,'.codex'),TMPDIR:tmpdir(),NO_COLOR:'1',TERM:'dumb',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1',DISABLE_AUTOUPDATER:'1',
        MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${server.address().port}/v1`,protocol,model:'test-model',key:'fake-test-key'})};
      const exe=mode==='CODEX'?codex:process.execPath;
      const args=mode==='CODEX'?['exec','--skip-git-repo-check','--json','--image',fixture,'--',prompt]: [claude,'-p','--input-format','stream-json','--output-format','stream-json','--verbose'];
      const child=spawn(process.execPath,[bridge,mode,exe,...args],{cwd:work,env,stdio:['pipe','pipe','pipe']});
      if(mode==='CLAUDE') child.stdin.end(JSON.stringify({type:'user',message:{role:'user',content:[{type:'text',text:prompt},{type:'image',source:{type:'base64',media_type:'image/png',data:imageData}}]}})+'\n');
      else child.stdin.end();
      let output='';child.stdout.on('data',c=>output+=c);child.stderr.on('data',c=>output+=c);
      const timer=setTimeout(()=>child.kill('SIGTERM'),60000);
      let code;
      try { [code]=await once(child,'exit'); }
      finally {clearTimeout(timer);server.closeAllConnections();server.close();}
      if (mode==='CODEX' && protocol==='messages') {
        assert.notEqual(code,0,output);
        assert.match(output,/detail/);
        assert.equal(count,0,'Unrepresentable detail must fail before upstream dispatch');
        console.log('PASS CODEX -> messages: explicit image detail rejected without losing parameters');
        continue;
      }
      assert.equal(code,0,output);
      assert.ok(output.includes('IMAGE_INPUT_OK'),output);
      assert.ok(imageSeen,`${mode}/${protocol}: image bytes missing or changed`);
      assert.equal(count,1);
      console.log(`PASS ${mode} -> ${protocol}: native CLI image bytes reached gateway`);
    }
  } finally {rmSync(root,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
