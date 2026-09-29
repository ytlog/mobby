'use strict';
// Real pinned Claude CLI permission control protocol, with fake model and isolated HOME only.
// MOBBY_TEST_ADB=/path/to/adb uses the installed Android app with the same isolated protocol gate.
// MOBBY_TEST_CLAUDE_JS=/path/to/cli.js node runtime/gateway-tests/approval-smoke.cjs
const {createServer}=require('node:http');
const {spawn,execFileSync}=require('node:child_process');
const {createInterface}=require('node:readline');
const {mkdtempSync,mkdirSync,existsSync,readFileSync,rmSync}=require('node:fs');
const {tmpdir}=require('node:os');
const {join,resolve}=require('node:path');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const bridge=resolve(__dirname,'../../runtime/android/src/main/assets/gateway/bridge.cjs');
async function main() {
  const adb=process.env.MOBBY_TEST_ADB,cli=process.env.MOBBY_TEST_CLAUDE_JS;
  assert.ok(adb || cli,'Set MOBBY_TEST_ADB for device or MOBBY_TEST_CLAUDE_JS for host');
  const quote=value=>"'"+String(value).replaceAll("'","'\\''")+"'";
  const remote=args=>execFileSync(adb,['shell',['run-as','com.github.ytlog.mobby.android',...args].map(quote).join(' ')],{encoding:'utf8',timeout:10000,stdio:['ignore','pipe','pipe']}).trim();
  const appHome=adb?remote(['pwd']):null,prefix=appHome+'/files/libtermux/usr';
  const native=adb?remote(['readlink',prefix+'/bin/claude']).replace(/\/[^/]+$/,''):null;
  const root=adb?appHome+'/files/approval-smoke-'+require('node:crypto').randomBytes(6).toString('hex'):mkdtempSync(join(tmpdir(),'mobby-approval-'));
  const exists=path=>{if(!adb)return existsSync(path);try{remote(['/system/bin/test','-e',path]);return true;}catch(error){if(error.status===1)return false;throw error;}};
  const read=path=>adb?execFileSync(adb,['shell',['run-as','com.github.ytlog.mobby.android','cat',path].map(quote).join(' ')],{encoding:'utf8'}):readFileSync(path,'utf8');
  const servers=new Set(),reverses=new Set();
  try {
    for(const choice of ['deny','allow','cancel']) {
      const home=join(root,choice),work=join(home,'work');if(adb)remote(['mkdir','-p',work,home+'/tmp']);else mkdirSync(work,{recursive:true});
      const target=join(work,'approved.txt'),content='PERMISSION_FIXTURE_OK\n';
      let requests=0,approvals=0,result,controlError,toolResult=false;
      const server=createServer(async(req,res)=>{
        if(req.method!=='POST'||req.url.split('?')[0]!=='/v1/messages'){res.writeHead(404);res.end();return;}
        const chunks=[];for await(const chunk of req)chunks.push(chunk);
        const body=JSON.parse(Buffer.concat(chunks));requests++;
        if(requests>1) toolResult=body.messages.some(m=>Array.isArray(m.content)&&m.content.some(c=>c.type==='tool_result'));
        const calls=requests===1?[{id:'call_write',function:{name:'Write',arguments:JSON.stringify({file_path:target,content})}}]:[];
        sendNative(res,nativeResponse({content:calls.length?'':'APPROVAL_TEST_FINISHED',calls,input:10,output:4},'messages','test-model'),'messages',Boolean(body.stream));
      });
      servers.add(server);
      server.listen(0,'127.0.0.1');await once(server,'listening');
      let reversePort;
      if(adb) {reversePort=execFileSync(adb,['reverse','tcp:0','tcp:'+server.address().port],{encoding:'utf8'}).trim();assert.match(reversePort,/^\d+$/);reverses.add(reversePort);}
      const env={PATH:process.env.PATH,HOME:home,TMPDIR:tmpdir(),NO_COLOR:'1',DISABLE_AUTOUPDATER:'1',
        MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${reversePort||server.address().port}/v1`,protocol:'messages',model:'test-model',key:'fake-permission-key'})};
      const args=['-p','--input-format','stream-json','--output-format','stream-json','--verbose','--permission-prompt-tool','stdio'];
      let child;
      if(adb) {
        Object.assign(env,{HOME:home,PREFIX:prefix,PATH:prefix+'/bin:/system/bin',TMPDIR:home+'/tmp',SHELL:prefix+'/bin/bash',LD_LIBRARY_PATH:prefix+'/lib:'+native,SSL_CERT_FILE:prefix+'/etc/tls/cert.pem'});
        const command=['env','-i',...Object.entries(env).map(([k,v])=>k+'='+v),prefix+'/bin/node',appHome+'/files/gateway.cjs','CLAUDE',prefix+'/bin/claude',...args];
        const script='echo $$ > '+quote(home+'/process-group')+'; cd '+quote(work)+' && exec '+command.map(quote).join(' ');
        child=spawn(adb,['shell','-T',['run-as','com.github.ytlog.mobby.android','/system/bin/toybox','setsid','-w','/system/bin/sh','-c',script].map(quote).join(' ')],{detached:true,stdio:['pipe','pipe','pipe']});
      } else child=spawn(process.execPath,[bridge,'CLAUDE',process.execPath,cli,...args],{env,cwd:work,detached:true,stdio:['pipe','pipe','pipe']});

      let diagnostics='';child.stderr.on('data',c=>diagnostics=(diagnostics+c).slice(-2000));
      const send=value=>child.stdin.write(JSON.stringify(value)+'\n');
      const lines=createInterface({input:child.stdout});
      lines.on('line',line=>{
        try {
          const event=JSON.parse(line);
          if(event.type==='control_response' && event.response?.request_id==='initialize-test') {
            assert.equal(event.response.subtype,'success');
            send({type:'user',message:{role:'user',content:'Write approved.txt with the supplied test content; ask for permission when required.'}});
          } else if(event.type==='control_request') {
            assert.equal(event.request.subtype,'can_use_tool');assert.equal(event.request.tool_name,'Write');
            assert.deepEqual(event.request.input,{file_path:target,content});
            assert.equal(exists(target),false,'Tool executed before the decision');approvals++;
            if(choice==='cancel') {
              if(adb) {const pid=remote(['cat',home+'/process-group']);assert.match(pid,/^[1-9][0-9]*$/);remote(['/system/bin/toybox','kill','-TERM','--','-'+pid]);}
              else child.kill('SIGTERM');
              return;
            }
            send({type:'control_response',response:{subtype:'success',request_id:event.request_id,response:choice==='allow'?{behavior:'allow',updatedInput:event.request.input}:{behavior:'deny',message:'Fixture user denied this write'}}});
          } else if(event.type==='result') {result=event;child.stdin.end();}
        } catch(error) {controlError=error;child.stdin.end();}
      });
      child.stdin.on('error',error=>{controlError ||= error;});
      send({type:'control_request',request_id:'initialize-test',request:{subtype:'initialize',hooks:null}});
      const timer=setTimeout(()=>child.kill('SIGTERM'),45000);
      let code;
      try { [code]=await once(child,'exit'); }
      finally {
        clearTimeout(timer);try{process.kill(-child.pid,'SIGKILL');}catch{}
        if(adb) {
          try {const pid=remote(['cat',home+'/process-group']);if(/^[1-9][0-9]*$/.test(pid))remote(['/system/bin/toybox','kill','-KILL','--','-'+pid]);}catch{}
          execFileSync(adb,['reverse','--remove','tcp:'+reversePort]);reverses.delete(reversePort);
        }
        lines.close();server.closeAllConnections();server.close();
      }
      if(controlError) throw controlError;
      if(choice==='cancel') {
        assert.notEqual(code,0,'Stopping pending approval must not report successful exit');
        assert.equal(approvals,1);assert.equal(exists(target),false);assert.equal(requests,1);
        console.log('PASS Claude cancel: pending approval stops without executing the write');
        continue;
      }
      assert.equal(code,0,diagnostics);assert.ok(result,'No CLI terminal result');
      assert.equal(approvals,1,'No real permission request received');assert.ok(toolResult,'No actual tool result returned to model');
      assert.equal(exists(target),choice==='allow','Permission decision was not enforced');
      if(choice==='allow') assert.equal(read(target),content);
      else assert.ok(result.permission_denials?.length,'Denied action must be retained in CLI terminal evidence');
      console.log(`PASS Claude ${choice}: native approval precedes tool execution and choice is enforced`);
    }
  } finally {
    for(const server of servers){server.closeAllConnections();server.close();}
    try {for(const port of reverses)execFileSync(adb,['reverse','--remove','tcp:'+port]);}
    finally {if(adb)remote(['rm','-rf',root]);else rmSync(root,{recursive:true,force:true});}
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
