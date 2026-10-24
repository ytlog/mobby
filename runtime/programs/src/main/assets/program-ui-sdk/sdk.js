// mobby SDK for program pages. Calls travel over the origin-bound bridge into the same
// permission checks and records as background calls; failures reject with MobbyError.
const bridge = globalThis.mobbyBridge;
const pending = new Map();
const listeners = new Set();
let next = 0;

export class MobbyError extends Error {
  constructor(code, reason, layer, effect) {
    super(`${code}: ${reason}`);
    this.name = 'MobbyError';
    this.code = code;
    this.reason = reason;
    this.layer = layer || null;
    this.effect = effect || 'none';
  }
}

if (bridge) {
  bridge.onmessage = (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === 'ui.message') { listeners.forEach((listener) => listener(message.payload)); return; }
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    if (message.error) waiter.reject(new MobbyError(message.error.code, message.error.reason, message.error.layer, message.effect));
    else if (message.status === 'succeeded') waiter.resolve(message.result === undefined ? null : message.result);
    else if (message.status === 'simulated') waiter.resolve({ simulated: true });
    else waiter.reject(new MobbyError('EFFECT_UNKNOWN', message.status || 'unknown', null, message.effect || 'unknown'));
  };
}

function send(body) {
  if (!bridge) return Promise.reject(new MobbyError('CONDITION_UNMET', 'page_not_opened_by_mobby'));
  const id = `m-${++next}`;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    bridge.postMessage(JSON.stringify({ ...body, id }));
  });
}

export function call(capability, args = {}) { return send({ type: 'call', capability, args }); }

function response(result) {
  return {
    status: result.status,
    headers: result.headers || {},
    file: result.file || null,
    text: async () => result.body,
    json: async () => JSON.parse(result.body),
  };
}

const mobby = {
  MobbyError,
  call,
  permissions: { list: () => send({ type: 'permissions' }) },
  kv: {
    get: (key) => call('storage.kv', { op: 'get', key }),
    set: (key, value) => call('storage.kv', { op: 'set', key, value }),
    delete: (key) => call('storage.kv', { op: 'delete', key }),
  },
  sql: {
    query: (sql, params = []) => call('storage.sql', { op: 'query', sql, params }),
    execute: (sql, params = []) => call('storage.sql', { op: 'execute', sql, params }),
    transaction: (statements) => call('storage.sql', { op: 'transaction', statements }),
  },
  files: {
    read: (path) => call('storage.files', { op: 'read', path }),
    write: (path, text) => call('storage.files', { op: 'write', path, text }),
    list: (dir = '') => call('storage.files', { op: 'list', dir }),
    delete: (path) => call('storage.files', { op: 'delete', path }),
  },
  net: {
    fetch: async (url, init = {}) => response(await call('net.http', {
      url, method: init.method || 'GET', headers: init.headers || {}, ...(init.body === undefined ? {} : { body: String(init.body) }),
    })),
  },
  secrets: {
    put: (name, value, options = {}) => call('secrets', { op: 'put', name, value, domains: options.domains || [] }),
    list: () => call('secrets', { op: 'list' }),
    delete: (name) => call('secrets', { op: 'delete', name }),
  },
  config: { get: () => call('config', {}) },
  notify: { show: (options) => call('notify.show', options) },
  log: (message, data) => call('log', { message: String(message), ...(data === undefined ? {} : { data }) }),
  ui: {
    send: (message) => send({ type: 'ui.send', message }),
    onMessage: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
  },
};

if (bridge) send({ type: 'hello' }).catch(() => {});

export default mobby;
