'use strict';
// Starts one program run: reads the run credential from stdin, loads the program's background
// entry, then dispatches events from /v1/events to the handlers it registered, one at a time.
const path = require('path');
const { pathToFileURL } = require('url');
const mobby = require('mobby');

const runner = mobby[Symbol.for('mobby.runner')];
const port = Number(process.env.MOBBY_API_PORT);
const entry = process.env.MOBBY_ENTRY;

function readCredential() {
  return new Promise((resolve, reject) => {
    let buffer = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => {
      buffer += chunk;
      const end = buffer.indexOf('\n');
      if (end >= 0) { process.stdin.pause(); resolve(buffer.slice(0, end)); }
    });
    process.stdin.on('end', () => (buffer ? resolve(buffer.trim()) : reject(new Error('no credential on stdin'))));
    process.stdin.on('error', reject);
  });
}

async function load(file) {
  if (path.extname(file) === '.mjs') return import(pathToFileURL(file).href);
  return require(file);
}

async function report(message, data) {
  try { await mobby.log(message, data); } catch (error) { process.stderr.write(`${message}: ${JSON.stringify(data)}\n`); }
}

// Events this run did not handle; any makes the run exit 1 so its record does not read as a success.
let unhandled = 0;

async function dispatch(event) {
  const handler = runner.handler(event.handler);
  if (!handler) { unhandled++; return report('handler_missing', { handler: event.handler, type: event.type }); }
  try {
    await handler(event.payload, { event });
  } catch (error) {
    // A failing handler is recorded; the run continues with the next event.
    unhandled++;
    await report('handler_failed', { handler: event.handler, error: String(error && error.stack || error) });
  }
}

async function stream(credential) {
  const response = await fetch(`http://127.0.0.1:${port}/v1/events`, {
    headers: { Authorization: `Bearer ${credential}`, Accept: 'text/event-stream' },
  });
  if (!response.ok) throw new Error(`event stream refused: ${response.status}`);
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let split;
    while ((split = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      const name = /^event: (.*)$/m.exec(block)?.[1];
      const data = /^data: (.*)$/m.exec(block)?.[1];
      if (!name || data === undefined) continue;
      if (name === 'run.stopping') return;
      if (name === 'program.event') await dispatch(JSON.parse(data));
    }
  }
}

(async () => {
  if (!port || !entry) throw new Error('runner must be started by mobby');
  const credential = await readCredential();
  runner.start(port, credential);
  await load(entry);
  await stream(credential);
  process.exit(unhandled > 0 ? 1 : 0);
})().catch((error) => {
  process.stderr.write(`runner: ${error && error.stack || error}\n`);
  process.exit(2);
});
