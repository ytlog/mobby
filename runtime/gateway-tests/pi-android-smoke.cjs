'use strict';
// Requires installed app + androidTest APK. Synthetic gateway only, no user configuration.
const {createServer}=require('node:http');
const {execFile}=require('node:child_process');
const {once}=require('node:events');
const assert=require('node:assert/strict');
const {nativeResponse,sendNative}=require('./native-fixture.cjs');
const adb=process.env.MOBBY_TEST_ADB||'adb';
const command=args=>new Promise(resolve=>execFile(adb,args,{timeout:240000,maxBuffer:2*1024*1024},(error,stdout,stderr)=>resolve({code:error?.code??0,stdout,stderr})));
async function main() {
  let requests=0,image=false,tool=false,resume=false,cancel=false,closed=false,port;
  const server=createServer(async(req,res)=>{
    if(req.method!=='POST'||req.url!=='/v1/responses'){res.writeHead(404);res.end();return;}
    const chunks=[];for await(const c of req)chunks.push(c);
    const body=JSON.parse(Buffer.concat(chunks));requests++;
    assert.equal(req.headers.authorization,'Bearer fake-pi-device-key');
    const input=body.input;
    const lastUser=input.findLastIndex(i=>i.role==='user');
    const prompt=JSON.stringify(input[lastUser]);
    image ||= JSON.stringify(body).includes('iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB');
    tool ||= JSON.stringify(body).includes('ANDROID_PI_FIXTURE_OK');
    if(prompt.includes('CANCEL_DEVICE')){
      cancel=true;res.writeHead(200,{'content-type':'text/event-stream'});res.write(': waiting\n\n');
      res.on('close',()=>closed=true);return;
    }
    resume ||= prompt.includes('ANDROID_PI_') && JSON.stringify(body).includes('SECOND_DEVICE_TURN');
    const calls=prompt.includes('ANDROID_PI_') && !input.slice(lastUser+1).some(i=>i.type==='function_call_output') && !JSON.stringify(body).includes('SECOND_DEVICE_TURN') ?
      [['read',{path:'fixture.txt'}],['write',{path:'result.txt',content:'ANDROID_PI_WRITE_OK'}]].map(([name,args],i)=>({id:'call_device_'+i,type:'function',function:{name,arguments:JSON.stringify(args)}})):[];
    sendNative(res,nativeResponse({content:calls.length?'':'ANDROID_PI_REPLY_OK',calls,input:14,output:6},'responses','test-model'),'responses',Boolean(body.stream));
  });
  server.listen(0,'127.0.0.1');await once(server,'listening');
  try {
    const reversed=await command(['reverse','tcp:0','tcp:'+server.address().port]);
    assert.equal(reversed.code,0);port=reversed.stdout.trim();assert.match(port,/^\d+$/);
    const endpoint=`http://127.0.0.1.nip.io:${port}/v1`;
    const result=await command(['shell','am','instrument','-w','-e','class','com.github.ytlog.mobby.android.PiRpcDeviceTest','-e','piMockEndpoint',endpoint,
      'com.github.ytlog.mobby.android.test/androidx.test.runner.AndroidJUnitRunner']);
    assert.equal(result.code,0,result.stderr);
    assert.match(result.stdout,/OK \(1 test\)/,result.stdout);
    assert.ok(requests>=5 && image && tool && resume && cancel && closed,
      JSON.stringify({requests,image,tool,resume,cancel,closed}));
    console.log('PASS Android Pi: packaged CLI, Android DNS bridge, images, real read/write, live turns, cold resume and confirmed cancellation');
  } finally {
    server.closeAllConnections();server.close();
    if(port)await command(['reverse','--remove','tcp:'+port]);
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
