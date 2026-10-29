'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SDK = path.join(__dirname, '../src/main/assets/program-sdk');
const mobby = require(path.join(SDK, 'node_modules/mobby'));

/** Fake program API: records requests and answers with the handler the test installs. */
function server(handler) {
  const requests = [];
  const instance = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      const entry = { method: req.method, url: req.url, auth: req.headers.authorization, body: body ? JSON.parse(body) : undefined };
      requests.push(entry);
      handler(entry, res, req);
    });
  });
  return new Promise((resolve) => instance.listen(0, '127.0.0.1', () => resolve({ instance, requests, port: instance.address().port })));
}

function json(res, value, status = 200) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(value));
}

test('calls return results and throw program errors without pretending success', async () => {
  const api = await server((req, res) => {
    const capability = req.body && req.body.capability;
    if (capability === 'notes.write') return json(res, { requestId: req.body.requestId, status: 'succeeded', effect: 'confirmed', result: { saved: true } });
    if (capability === 'sms.send') return json(res, { requestId: req.body.requestId, status: 'failed', effect: 'none', error: { code: 'PERMISSION_DENIED', layer: 'program', reason: 'program_off' } });
    if (capability === 'dry.write') return json(res, { requestId: req.body.requestId, status: 'simulated', effect: 'none' });
    return json(res, { requestId: req.body.requestId, status: 'unknown', effect: 'unknown', error: { code: 'EFFECT_UNKNOWN', reason: 'radio' } });
  });
  mobby[Symbol.for('mobby.runner')].start(api.port, 'secret-credential');
  try {
    assert.deepEqual(await mobby.call('notes.write', { text: 'hi' }), { saved: true });
    assert.equal(api.requests[0].auth, 'Bearer secret-credential');
    assert.equal(api.requests[0].body.schema, 'mobby.program-api/1');
    await assert.rejects(mobby.call('sms.send', {}), (error) => error.code === 'PERMISSION_DENIED' && error.layer === 'program' && error.reason === 'program_off');
    assert.deepEqual(await mobby.call('dry.write'), { simulated: true });
    await assert.rejects(mobby.call('sms.list'), (error) => error.code === 'EFFECT_UNKNOWN' && error.effect === 'unknown');
  } finally { api.instance.close(); }
});

test('a simulated fetch says so and has no body to read', async () => {
  const api = await server((req, res) => json(res, { requestId: req.body.requestId, status: 'simulated', effect: 'none' }));
  mobby[Symbol.for('mobby.runner')].start(api.port, 'c');
  try {
    const response = await mobby.net.fetch('https://api.example.com/x');
    assert.equal(response.simulated, true);
    assert.equal(response.status, null);
    await assert.rejects(response.json(), (error) => error.name === 'MobbyError' && error.code === 'SIMULATED' && error.reason === 'dry_run_has_no_response_body');
    await assert.rejects(response.text(), (error) => error.code === 'SIMULATED');
  } finally { api.instance.close(); }
});

test('a lost connection looks up the original request instead of resending it', async () => {
  const api = await server((req, res, raw) => {
    if (req.method === 'POST') return raw.socket.destroy();
    if (req.url.endsWith('/lost')) return json(res, { error: { code: 'RESOURCE_MISSING', reason: 'unknown_request' } }, 404);
    return json(res, { requestId: 'kept', status: 'failed', effect: 'none', error: { code: 'TIMEOUT', reason: 'deadline_exceeded' } });
  });
  mobby[Symbol.for('mobby.runner')].start(api.port, 'c');
  try {
    await assert.rejects(mobby.call('notes.write', {}, { requestId: 'kept' }), (error) => error.code === 'TIMEOUT');
    await assert.rejects(mobby.call('notes.write', {}, { requestId: 'lost' }), (error) => error.code === 'EFFECT_UNKNOWN' && error.reason === 'request_not_received');
    assert.equal(api.requests.filter((r) => r.method === 'POST').length, 2);
    assert.deepEqual(api.requests.filter((r) => r.method === 'GET').map((r) => r.url), ['/v1/operations/kept', '/v1/operations/lost']);
  } finally { api.instance.close(); }
});

/** Runs the real runner against a fake API that sends [events] and then run.stopping; resolves with the exit code. */
async function runRunner(source, events) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'program-'));
  const entry = path.join(dir, 'main.js');
  fs.writeFileSync(entry, source);
  const api = await server((req, res) => {
    if (req.url === '/v1/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      const send = (name, data) => res.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
      events.forEach((event) => send('program.event', event));
      setTimeout(() => { send('run.stopping', { reason: 'idle' }); res.end(); }, 300);
      return;
    }
    json(res, { requestId: req.body.requestId, status: 'succeeded', effect: 'confirmed', result: null });
  });
  try {
    const child = spawn(process.execPath, [path.join(SDK, 'runner.cjs')], {
      env: { ...process.env, NODE_PATH: path.join(SDK, 'node_modules'), MOBBY_API_PORT: String(api.port), MOBBY_ENTRY: entry },
    });
    child.stdin.end('run-credential\n');
    return { code: await new Promise((resolve) => child.on('exit', resolve)), requests: api.requests };
  } finally { api.instance.close(); fs.rmSync(dir, { recursive: true, force: true }); }
}

/** On the tablet a page action failed in its handler, yet the run ended with code 0 and the program list said it was fine. */
test('a run whose handlers all succeed exits 0', async () => {
  const { code } = await runRunner(`const mobby = require('mobby'); mobby.on('onTick', async () => {});`,
    [{ eventId: 'e1', type: 'time.schedule', handler: 'onTick', payload: {} }]);
  assert.equal(code, 0);
});

test('the runner reads its credential, dispatches events and exits on run.stopping', async () => {
  const { code, requests } = await runRunner(`const mobby = require('mobby');
    mobby.on('onTick', async (payload) => { await mobby.call('notes.write', { text: payload.text }); });
    mobby.on('broken', async () => { throw new Error('boom'); });`, [
    { eventId: 'e1', type: 'time.schedule', handler: 'onTick', payload: { text: 'tick' } },
    { eventId: 'e2', type: 'time.schedule', handler: 'broken', payload: {} },
    { eventId: 'e3', type: 'time.schedule', handler: 'missing', payload: {} },
  ]);
  // Later events still run after a failure, but the run reports that it did not handle everything.
  assert.equal(code, 1);
  const posts = requests.filter((r) => r.method === 'POST');
  assert.ok(requests.every((r) => r.auth === 'Bearer run-credential'));
  assert.deepEqual(posts.map((r) => r.body.capability), ['notes.write', 'log', 'log']);
  assert.equal(posts[0].body.args.text, 'tick');
  assert.equal(posts[1].body.args.message, 'handler_failed');
  assert.equal(posts[2].body.args.message, 'handler_missing');
});

test('the runner refuses to start without mobby', async () => {
  const child = spawn(process.execPath, [path.join(SDK, 'runner.cjs')], { env: { ...process.env, NODE_PATH: path.join(SDK, 'node_modules') } });
  child.stdin.end();
  assert.equal(await new Promise((resolve) => child.on('exit', resolve)), 2);
});

/** Runs check.cjs as the launcher does: parse only, one result per script that does not parse. */
function checkSyntax(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'check-'));
  for (const [name, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
    fs.writeFileSync(path.join(dir, name), text);
  }
  const child = require('node:child_process').spawnSync(process.execPath,
    [path.join(SDK, 'check.cjs'), dir, ...Object.keys(files)], { encoding: 'utf8' });
  fs.rmSync(dir, { recursive: true, force: true });
  assert.equal(child.status, 0, child.stderr);
  return JSON.parse(child.stdout);
}

/** On the tablet a page used await in a non-async callback; the module never ran and validate said nothing. */
test('the syntax check reports scripts that cannot be parsed, with their line', () => {
  const problems = checkSyntax({
    'main.js': "const mobby = require('mobby');\nmobby.on('x', async () => {});\n",
    'lib.mjs': "export const a = 1;\n",
    'ui/app.js': "import mobby from '/_mobby/sdk.js';\nmobby.ui.onMessage((m) => {\n  await load();\n});\n",
    'ui/classic.js': "with (Math) { max(1, 2); }\n",
    'broken.cjs': "module.exports = {\n",
  });
  assert.deepEqual(problems.map((p) => [p.path, p.line]), [['ui/app.js', 3], ['broken.cjs', 2]]);
  assert.match(problems[0].message, /SyntaxError/);
});

test('the syntax check never runs the code it parses', () => {
  const marker = path.join(os.tmpdir(), `ran-${process.pid}`);
  assert.deepEqual(checkSyntax({ 'main.js': `require('fs').writeFileSync(${JSON.stringify(marker)}, 'x');` }), []);
  assert.equal(fs.existsSync(marker), false);
});
