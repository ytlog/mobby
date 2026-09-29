'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {mkdtempSync, writeFileSync, chmodSync, readFileSync, rmSync} = require('node:fs');
const {tmpdir} = require('node:os');
const {join} = require('node:path');
const {spawn} = require('node:child_process');

test('OpenCode keeps one server across completed turns and accepts a second stdin prompt', async () => {
  const home = mkdtempSync(join(tmpdir(), 'mobby-opencode-live-'));
  const fixture = join(home, 'fake-opencode.cjs');
  const executable = join(home, 'opencode');
  const log = join(home, 'calls.log');
  writeFileSync(executable, `#!/bin/sh\nexec "${process.execPath}" "${fixture}" "$@"\n`);
  chmodSync(executable, 0o755);
  writeFileSync(fixture, `const http=require('node:http');const fs=require('node:fs');
const args=process.argv.slice(2);fs.appendFileSync(process.env.MOBBY_TEST_LOG,JSON.stringify(args)+'\\n');
if(args[0]==='serve'){
 const port=Number(args[args.indexOf('--port')+1]);
 http.createServer((req,res)=>{if(!req.headers.authorization){res.writeHead(401);res.end();return}res.setHeader('content-type','application/json');res.end('{}');}).listen(port,'127.0.0.1');
}else if(args[0]==='run'){
 const prompt=args.at(-1);
 process.stdout.write(JSON.stringify({type:'step_start',sessionID:'ses_persisted',part:{type:'step-start'}})+'\\n');
 process.stdout.write(JSON.stringify({type:'text',sessionID:'ses_persisted',part:{id:'part-'+prompt,text:'answer-'+prompt}})+'\\n');
 process.stdout.write(JSON.stringify({type:'step_finish',sessionID:'ses_persisted',part:{reason:'stop'}})+'\\n');
 setInterval(()=>{},1000);
}`);
  const bridge = join(__dirname, '../../runtime/android/src/main/assets/gateway/bridge.cjs');
  const child = spawn(process.execPath, [bridge, 'OPEN_CODE', executable, 'serve', '--pure', '--hostname', '127.0.0.1'], {
    env:{...process.env, MOBBY_TEST_LOG:log,
      MOBBY_GATEWAY_CONFIG:JSON.stringify({endpoint:'http://127.0.0.1:9/v1',protocol:'responses',model:'test',key:'fixture'})},
    stdio:['pipe','pipe','pipe']
  });
  let output = '';
  let errors = '';
  child.stdout.on('data', chunk => { output += chunk.toString(); });
  child.stderr.on('data', chunk => { errors += chunk.toString(); });
  try {
    const waitFor = async marker => {
      const deadline = Date.now()+10_000;
      while (!output.includes(marker)) {
        if (Date.now()>deadline || child.exitCode !== null) throw new Error('OpenCode output missing '+marker+'; stdout='+output+'; stderr='+errors);
        await new Promise(resolve=>setTimeout(resolve,25));
      }
    };
    child.stdin.write(JSON.stringify({type:'prompt',text:'one',model:'test',images:[]})+'\n');
    await waitFor('answer-one');
    await waitFor('"reason":"stop"');
    child.stdin.write(JSON.stringify({type:'prompt',text:'two',model:'test',sessionId:'ses_persisted',images:[]})+'\n');
    await waitFor('answer-two');
    const calls = readFileSync(log,'utf8').trim().split('\n').map(JSON.parse);
    assert.deepEqual(calls.map(args=>args[0]),['serve','run','run']);
    assert.ok(calls[0].includes('--pure'));
    assert.deepEqual(calls[0].slice(calls[0].indexOf('--hostname'),calls[0].indexOf('--hostname')+2),
      ['--hostname','127.0.0.1']);
    assert.ok(calls[1].includes('--attach') && calls[1].includes('--auto'));
    assert.ok(!calls[1].includes('--session'));
    assert.deepEqual(calls[2].slice(calls[2].indexOf('--session'),calls[2].indexOf('--session')+2),
      ['--session','ses_persisted']);
    assert.equal(child.exitCode,null);
  } finally {
    child.kill('SIGTERM');
    if (child.exitCode === null && child.signalCode === null)
      await Promise.race([new Promise(resolve=>child.once('exit',resolve)),new Promise(resolve=>setTimeout(resolve,2000))]);
    rmSync(home,{recursive:true,force:true});
  }
});
