'use strict';
// Host fixture transport for testing the production Kotlin control session against pinned Claude.
const {createServer}=require('node:http');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {join,resolve}=require('node:path');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const [cli,work,resume]=process.argv.slice(2);
let child,closing=false,requests=0;
const server=createServer(async(req,res)=>{
  if(req.method!=='POST'||req.url.split('?')[0]!=='/v1/messages'){res.writeHead(404);res.end();return;}
  const chunks=[];for await(const c of req)chunks.push(c);
  const body=JSON.parse(Buffer.concat(chunks));requests++;
  if(requests===1) {
    // Check the newest user message: an image from resumed history must not satisfy this gate.
    const content=body.messages.filter(message=>message.role==='user').at(-1)?.content;
    const images=Array.isArray(content)?content.filter(block=>block.type==='image'):[];
    try {
      assert.equal(images.length,1,'Newest user message must include its image');
      assert.deepEqual(images[0].source,{type:'base64',media_type:'image/png',data:'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4z8AARAxg8j8AG/ID/fPnS7EAAAAASUVORK5CYII='});
    } catch { cleanup(1); return; }
  }
  const calls=requests===1?[{id:'write-fixture',function:{name:'Write',arguments:JSON.stringify({file_path:join(work,'approved.txt'),content:'KOTLIN_CONTROL_OK\n'})}}]:[];
  sendNative(res,nativeResponse({content:calls.length?'':'FIXTURE_FINISHED',calls,input:10,output:5},'messages','test-model'),'messages',Boolean(body.stream));
});
function cleanup(code) {
  if(closing)return;closing=true;
  if(child)try{process.kill(-child.pid,'SIGKILL');}catch{}
  server.closeAllConnections();server.close();process.exit(code);
}
process.on('SIGTERM',()=>cleanup(143));process.on('SIGINT',()=>cleanup(130));
server.listen(0,'127.0.0.1',()=>{
  child=spawn(process.execPath,[resolve(__dirname,'../../runtime/android/src/main/assets/gateway/bridge.cjs'),'CLAUDE',process.execPath,cli,
    '-p','--input-format','stream-json','--output-format','stream-json','--verbose','--permission-prompt-tool','stdio',...(resume?['--resume',resume]:[])],{
    cwd:work,detached:true,stdio:['pipe','pipe','pipe'],env:{PATH:process.env.PATH,HOME:work,TMPDIR:work,NO_COLOR:'1',DISABLE_AUTOUPDATER:'1',
      MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:`http://127.0.0.1:${server.address().port}/v1`,protocol:'messages',model:'test-model',key:'fake-control-key'})}});
  process.stdin.pipe(child.stdin);child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);
  child.stdin.on('error',()=>cleanup(1));child.on('error',()=>cleanup(1));child.on('exit',code=>{
    try{process.kill(-child.pid,'SIGKILL');}catch{}
    child.once('close',()=>cleanup(code===null?1:code));
  });
});
setTimeout(()=>cleanup(124),60000).unref();
