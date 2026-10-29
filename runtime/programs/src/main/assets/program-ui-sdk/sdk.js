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

const TYPES = {
  string: [(value) => typeof value === 'string', 'a string'],
  array: [Array.isArray, 'an array'],
  object: [(value) => value !== null && typeof value === 'object' && !Array.isArray(value), 'an object'],
};

function kind(value) {
  if (value === undefined || value === null) return String(value);
  if (Array.isArray(value)) return 'an array';
  return typeof value === 'object' ? 'an object' : `a ${typeof value}`;
}

/** Checks arguments before anything is sent; the error names the call's real form, since the API only sees the result. */
function expect(form, hint, args) {
  for (const [name, value, type] of args) {
    const [ok, expected] = TYPES[type];
    if (!ok(value)) throw new TypeError(`${form}: ${name} must be ${expected}, got ${kind(value)}. ${hint}`);
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

export async function call(capability, args = {}) {
  expect('call(capability, args)', "e.g. call('notify.show', { title: 'Done' })", [['capability', capability, 'string'], ['args', args, 'object']]);
  return send({ type: 'call', capability, args });
}

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
    get: async (key) => { expect('kv.get(key)', "e.g. kv.get('last')", [['key', key, 'string']]); return call('storage.kv', { op: 'get', key }); },
    set: async (key, value) => { expect('kv.set(key, value)', "Arguments are positional, e.g. kv.set('last', 42)", [['key', key, 'string']]); return call('storage.kv', { op: 'set', key, value }); },
    delete: async (key) => { expect('kv.delete(key)', "e.g. kv.delete('last')", [['key', key, 'string']]); return call('storage.kv', { op: 'delete', key }); },
  },
  sql: {
    query: async (sql, params = []) => {
      expect('sql.query(sql, params)', "Arguments are positional, e.g. sql.query('SELECT * FROM t WHERE id = ?', [id])", [['sql', sql, 'string'], ['params', params, 'array']]);
      return call('storage.sql', { op: 'query', sql, params });
    },
    execute: async (sql, params = []) => {
      expect('sql.execute(sql, params)', "Arguments are positional, e.g. sql.execute('INSERT INTO t VALUES (?, ?)', [a, b])", [['sql', sql, 'string'], ['params', params, 'array']]);
      return call('storage.sql', { op: 'execute', sql, params });
    },
    transaction: async (statements) => {
      expect('sql.transaction([{sql, params}])', "e.g. sql.transaction([{ sql: 'DELETE FROM t', params: [] }])", [['statements', statements, 'array']]);
      return call('storage.sql', { op: 'transaction', statements });
    },
  },
  files: {
    read: async (path) => { expect('files.read(path)', "e.g. files.read('report.csv')", [['path', path, 'string']]); return call('storage.files', { op: 'read', path }); },
    write: async (path, text) => {
      expect('files.write(path, text)', "Arguments are positional, e.g. files.write('report.csv', text)", [['path', path, 'string'], ['text', text, 'string']]);
      return call('storage.files', { op: 'write', path, text });
    },
    list: async (dir = '') => { expect('files.list(dir)', "e.g. files.list('') for the top folder", [['dir', dir, 'string']]); return call('storage.files', { op: 'list', dir }); },
    delete: async (path) => { expect('files.delete(path)', "e.g. files.delete('report.csv')", [['path', path, 'string']]); return call('storage.files', { op: 'delete', path }); },
  },
  net: {
    fetch: async (url, init = {}) => {
      expect('net.fetch(url, {method, headers, body})', "Arguments are positional, e.g. net.fetch('https://api.example.com/x', { method: 'GET' })",
        [['url', url, 'string'], ['init', init, 'object']]);
      return response(await call('net.http', {
        url, method: init.method || 'GET', headers: init.headers || {}, ...(init.body === undefined ? {} : { body: String(init.body) }),
      }));
    },
  },
  secrets: {
    put: async (name, value, options = {}) => {
      expect('secrets.put(name, value, {domains})', "Arguments are positional, e.g. secrets.put('token', value, { domains: ['api.example.com'] })",
        [['name', name, 'string'], ['value', value, 'string'], ['options', options, 'object']]);
      return call('secrets', { op: 'put', name, value, domains: options.domains || [] });
    },
    list: () => call('secrets', { op: 'list' }),
    delete: async (name) => { expect('secrets.delete(name)', "e.g. secrets.delete('token')", [['name', name, 'string']]); return call('secrets', { op: 'delete', name }); },
  },
  config: { get: () => call('config', {}) },
  notify: {
    show: async (options) => {
      expect('notify.show({title, body, openUi})', "e.g. notify.show({ title: 'Done', body: 'Saved 3 rows' })", [['options', options, 'object']]);
      return call('notify.show', options);
    },
  },
  log: (message, data) => call('log', { message: String(message), ...(data === undefined ? {} : { data }) }),
  ui: {
    send: (message) => send({ type: 'ui.send', message }),
    onMessage: (listener) => { listeners.add(listener); return () => listeners.delete(listener); },
  },
};

if (bridge) send({ type: 'hello' }).catch(() => {});

export default mobby;
