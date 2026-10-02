'use strict';
// Keep one OpenCode server and its gateway alive across turns. `run --attach` is only
// the event client for a turn; it does not own the session or its model connection.
const {spawn} = require('node:child_process');
const {createServer} = require('node:net');
const {randomBytes} = require('node:crypto');
const readline = require('node:readline');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function runOpenCodeLive(executable, serverArguments, environment, bridge) {
  const port = await freePort();
  const address = `http://127.0.0.1:${port}`;
  const password = randomBytes(32).toString('hex');
  const env = {...environment, OPENCODE_SERVER_PASSWORD:password};
  const server = spawn(executable, [...serverArguments, '--port', String(port)],
    {env, stdio:['ignore','pipe','pipe']});
  // Drain output without forwarding server diagnostics into the agent transcript.
  server.stdout.resume(); server.stderr.resume();
  let serverExited = false;
  let client = null;
  let active = false;
  let sessionId = null;
  let closed = false;
  const stop = () => {
    if (closed) return;
    closed = true;
    client?.kill('SIGTERM');
    server.kill('SIGTERM');
    bridge.close();
    process.stdin.destroy();
  };
  server.on('error', () => { serverExited = true; stop(); process.exitCode = 1; });
  server.on('exit', () => { serverExited = true; if (!closed) { stop(); process.exitCode = 1; } });
  for (const signal of ['SIGTERM','SIGINT']) process.on(signal, stop);
  const authorization = 'Basic ' + Buffer.from(`opencode:${password}`).toString('base64');
  let ready = false;
  for (let attempt=0; attempt<100 && !serverExited; attempt++) {
    try {
      const denied = await fetch(address + '/session/status', {signal:AbortSignal.timeout(1000)});
      await denied.body?.cancel();
      if (denied.status !== 401) { await delay(100); continue; }
      const response = await fetch(address + '/session/status',
        {headers:{authorization}, signal:AbortSignal.timeout(1000)});
      if (response.ok) { await response.body?.cancel(); ready = true; break; }
      await response.body?.cancel();
    } catch (_) {}
    await delay(100);
  }
  if (!ready) { stop(); throw new Error('OpenCode server did not become ready'); }

  const input = readline.createInterface({input:process.stdin, crlfDelay:Infinity});
  input.on('close', stop);
  input.on('line', raw => {
    let command;
    try { command = JSON.parse(raw); } catch (_) { return; }
    if (command.type !== 'prompt' || typeof command.text !== 'string' ||
      typeof command.model !== 'string' || !Array.isArray(command.images)) return;
    if (command.newSession !== undefined && typeof command.newSession !== 'boolean') {
      process.stdout.write(JSON.stringify({type:'error', error:{message:'Invalid OpenCode session selection'}}) + '\n');
      return;
    }
    if (active || closed) {
      process.stdout.write(JSON.stringify({type:'error', error:{message:'OpenCode is still running'}}) + '\n');
      return;
    }
    if (command.newSession === true && command.sessionId) {
      process.stdout.write(JSON.stringify({type:'error', error:{message:'Conflicting OpenCode session selection'}}) + '\n');
      return;
    }
    active = true;
    const selectedSession = command.newSession === true ? null : command.sessionId || sessionId;
    if (command.newSession === true) sessionId = null;
    const args = ['run', '--attach', address, '--format', 'json', '--pure', '--auto',
      '-m', `openai/${command.model}`];
    if (selectedSession) args.push('--session', selectedSession);
    for (const image of command.images) {
      if (typeof image.path !== 'string' || !image.path.startsWith('/') || image.path.includes('\0')) {
        active = false;
        process.stdout.write(JSON.stringify({type:'error',error:{message:'Invalid OpenCode image path'}}) + '\n');
        return;
      }
      args.push('--file', image.path);
    }
    args.push('--', command.text);
    const run = spawn(executable, args, {env, stdio:['ignore','pipe','pipe']});
    client = run;
    let ended = false;
    let buffer = '';
    const emit = line => {
      if (!line || client !== run) return;
      process.stdout.write(line + '\n');
      let event;
      try { event = JSON.parse(line); } catch (_) { return; }
      if (typeof event.sessionID === 'string' && /^ses_[A-Za-z0-9_-]{1,96}$/.test(event.sessionID)) sessionId = event.sessionID;
      if (event.type === 'error' || event.type === 'step_finish' &&
        ['stop','error'].includes(event.part?.reason)) {
        ended = true; active = false;
        // This OpenCode CLI can remain alive after its terminal event. The server stays.
        setTimeout(() => { if (run.exitCode === null) run.kill('SIGTERM'); }, 100);
      }
    };
    run.stdout.setEncoding('utf8');
    run.stdout.on('data', chunk => {
      buffer += chunk;
      let end;
      while ((end = buffer.indexOf('\n')) >= 0) { emit(buffer.slice(0,end).trimEnd()); buffer = buffer.slice(end+1); }
      if (buffer.length > 1024*1024) { run.kill('SIGTERM'); buffer = ''; }
    });
    run.stderr.resume();
    run.on('error', () => {
      if (!ended) emit(JSON.stringify({type:'error',error:{message:'OpenCode event client failed'}}));
    });
    run.on('exit', () => {
      if (buffer) emit(buffer.trimEnd());
      if (!ended) emit(JSON.stringify({type:'error',error:{message:'OpenCode turn ended without a terminal event'}}));
      if (client === run) client = null;
    });
  });
}

module.exports = {runOpenCodeLive};
