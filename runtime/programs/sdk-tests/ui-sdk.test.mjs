import test from 'node:test';
import assert from 'node:assert/strict';

// A fake origin-bound bridge: records messages and lets the test answer them.
const sent = [];
globalThis.mobbyBridge = { postMessage: (text) => sent.push(JSON.parse(text)), onmessage: null };
const reply = (message) => globalThis.mobbyBridge.onmessage({ data: JSON.stringify(message) });
const { default: mobby, MobbyError } = await import('../src/main/assets/program-ui-sdk/sdk.js');

test('page calls resolve, fail with MobbyError and receive background messages', async () => {
  assert.equal(sent[0].type, 'hello');
  const rows = mobby.sql.query('SELECT 1');
  const call = sent.at(-1);
  assert.deepEqual([call.type, call.capability, call.args.op], ['call', 'storage.sql', 'query']);
  reply({ id: call.id, status: 'succeeded', effect: 'none', result: { rows: [[1]] } });
  assert.deepEqual(await rows, { rows: [[1]] });

  const denied = mobby.net.fetch('https://api.example.com/x');
  reply({ id: sent.at(-1).id, status: 'failed', effect: 'none', error: { code: 'SCOPE_DENIED', reason: 'domain_not_granted', layer: 'program' } });
  await assert.rejects(denied, (error) => error instanceof MobbyError && error.code === 'SCOPE_DENIED' && error.layer === 'program');

  const unknown = mobby.notify.show({ title: 'x' });
  reply({ id: sent.at(-1).id, status: 'unknown', effect: 'unknown' });
  await assert.rejects(unknown, (error) => error.code === 'EFFECT_UNKNOWN');

  const received = [];
  mobby.ui.onMessage((payload) => received.push(payload));
  reply({ type: 'ui.message', payload: { type: 'updated' } });
  assert.deepEqual(received, [{ type: 'updated' }]);
  mobby.ui.send({ type: 'refresh' });
  assert.deepEqual(sent.at(-1).message, { type: 'refresh' });
});
