'use strict';
// Android Node owns networking. Native protocol bodies and streaming responses are retained.
// No protocol conversion; the CLI receives only a per-process loopback credential.
const http = require('node:http');
const fs = require('node:fs');
const {randomBytes} = require('node:crypto');
const {spawn} = require('node:child_process');
const {Readable} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const MAX_BODY = 16 * 1024 * 1024;
function endpoint(config) {
  if (!['responses', 'messages'].includes(config.protocol)) throw Error('网关协议无效');
  const url = new URL(config.endpoint);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw Error('网关 URL 无效');
  let path = url.pathname.replace(/\/+$/, '');
  path = path.replace(/\/(chat\/completions|responses|messages)$/, '');
  if (!path) path = '/v1';
  url.pathname = path + {responses:'/responses', messages:'/messages'}[config.protocol];
  return url.toString();
}
async function readBounded(body) {
  const chunks=[]; let size=0;
  for await (const chunk of body) { size+=chunk.length; if (size > MAX_BODY) throw Error('网关请求或响应超过 16 MiB'); chunks.push(Buffer.from(chunk)); }
  return Buffer.concat(chunks).toString('utf8');
}
async function createBridge(config) {
  const upstream = endpoint(config), token = randomBytes(32).toString('hex');
  const controllers = new Set();
  const server = http.createServer(async (req, res) => {
    const controller = new AbortController(); controllers.add(controller);
    const timer = setTimeout(() => controller.abort(), 300000);
    res.on('close', () => { if (!res.writableEnded) controller.abort(); });
    try {
      if (req.headers.authorization !== `Bearer ${token}` && req.headers['x-api-key'] !== token) { res.writeHead(401); res.end(); return; }
      const local = new URL(req.url, 'http://localhost');
      const routes = {'/v1/messages':['messages',''], '/v1/messages/count_tokens':['messages','/count_tokens'],
        '/v1/responses':['responses',''], '/v1/responses/compact':['responses','/compact']};
      const route = routes[local.pathname];
      if (req.method !== 'POST' || !route) { res.writeHead(404); res.end(JSON.stringify({error:{message:'不支持的本地网关路径'}})); return; }
      if (route[0] !== config.protocol) { res.writeHead(400); res.end(JSON.stringify({error:{message:'网关协议不匹配，暂不提供转换'}})); return; }
      if (req.headers['content-encoding'] && req.headers['content-encoding'] !== 'identity') {
        res.writeHead(415); res.end(JSON.stringify({error:{message:'不支持的请求压缩格式'}})); return;
      }
      let payload;
      try {
        const body = JSON.parse(await readBounded(req));
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw Error('不支持的请求格式');
        payload = {...body, model:config.model};
      } catch (error) { error.invalidInput = true; throw error; }
      const headers = {'content-type':'application/json'};
      if (config.key) {
        headers.authorization = `Bearer ${config.key}`;
        if (config.protocol === 'messages') headers['x-api-key'] = config.key;
      }
      if (config.protocol === 'messages') {
        headers['anthropic-version'] = req.headers['anthropic-version'] || '2023-06-01';
        if (req.headers['anthropic-beta']) headers['anthropic-beta'] = req.headers['anthropic-beta'];
      }
      if (config.protocol === 'responses') {
        for (const name of ['openai-beta', 'session_id', 'conversation_id', 'x-codex-turn-state', 'x-codex-turn-metadata']) {
          if (req.headers[name]) headers[name] = req.headers[name];
        }
      }
      const response = await fetch(upstream + route[1] + local.search, {method:'POST', headers, body:JSON.stringify(payload), redirect:'error', signal:controller.signal});
      if (!response.ok) {
        await response.body?.cancel();
        res.writeHead(response.status, {'content-type':'application/json'});
        res.end(JSON.stringify({type:'error', error:{type:'api_error', message:`网关 HTTP ${response.status}，请检查地址、协议、模型和密钥`}}));
      } else {
        const responseHeaders = {'content-type':response.headers.get('content-type') || 'application/json'};
        // Codex uses this opaque server value to continue a turn; do not drop it at the bridge.
        const turnState = response.headers.get('x-codex-turn-state');
        if (turnState) responseHeaders['x-codex-turn-state'] = turnState;
        res.writeHead(response.status, responseHeaders);
        await pipeline(Readable.fromWeb(response.body), res, {signal:controller.signal});
      }
    } catch (error) {
      // Once SSE headers/data are sent, an HTTP/JSON error would corrupt the stream.
      if (res.headersSent || res.destroyed) { res.destroy(); return; }
      if (!res.headersSent) res.writeHead(error.invalidInput ? 400 : 502, {'content-type':'application/json'});
      // Only local validation errors are useful; network errors may contain URLs or credentials.
      const message = /^(不支持|网关|请输入)/.test(error.message) ? error.message : '网关请求失败，请检查网络与协议配置';
      res.end(JSON.stringify({type:'error', error:{type:error.invalidInput ? 'invalid_request_error' : 'api_error', message}}));
    } finally { clearTimeout(timer); controllers.delete(controller); }
  });
  server.requestTimeout = 300000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {url:`http://127.0.0.1:${server.address().port}`, token, close:() => { for (const c of controllers) c.abort(); server.closeAllConnections(); server.close(); }};
}
function agentLaunch(mode, args, config, environment, bridge) {
  const protocol = mode === 'CLAUDE' ? 'messages' : mode === 'CODEX' || mode === 'OPEN_CODE' ? 'responses' : null;
  if (!protocol || config.protocol !== protocol) throw Error('网关协议必须与 Agent 原生协议一致；暂不提供转换');
  if (!bridge?.url || !bridge?.token) throw Error('必须使用本地桥接');
  const base = bridge.url, token = bridge.token;
  const env = {...environment};
  delete env.MOBBY_GATEWAY_CONFIG;
  delete env.MOBBY_AGENT_INPUT_FILE;
  const agentArgs = [...args];
  if (mode === 'CLAUDE') {
    Object.assign(env, {ANTHROPIC_BASE_URL:base, ANTHROPIC_AUTH_TOKEN:token, ANTHROPIC_API_KEY:'', ANTHROPIC_MODEL:config.model,
      ANTHROPIC_DEFAULT_OPUS_MODEL:config.model, ANTHROPIC_DEFAULT_SONNET_MODEL:config.model, ANTHROPIC_DEFAULT_HAIKU_MODEL:config.model,
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'});
  } else if (mode === 'CODEX') {
    env.MOBBY_GATEWAY_TOKEN = token;
    const options = [
      'model_provider="mobby"', `model=${JSON.stringify(config.model)}`,
      'model_providers.mobby.name="mobby Gateway"', `model_providers.mobby.base_url=${JSON.stringify(base + '/v1')}`,
      'model_providers.mobby.env_key="MOBBY_GATEWAY_TOKEN"', 'model_providers.mobby.wire_api="responses"',
      'model_providers.mobby.requires_openai_auth=false', 'model_providers.mobby.supports_websockets=false'
    ];
    agentArgs.unshift(...options.flatMap(value => ['-c', value]));
  } else if (mode === 'OPEN_CODE') {
    // Built-in openai provider always calls Responses. The inline config points only at the local bridge.
    env.OPENAI_API_KEY = token;
    Object.assign(env, {
      OPENCODE_DISABLE_AUTOUPDATE:'1', OPENCODE_DISABLE_MODELS_FETCH:'1', OPENCODE_DISABLE_LSP_DOWNLOAD:'1',
      OPENCODE_DISABLE_DEFAULT_PLUGINS:'1', OPENCODE_DISABLE_CLAUDE_CODE_SKILLS:'1',
      OPENCODE_CONFIG_CONTENT:JSON.stringify({
        model:'openai/' + config.model,
        provider:{openai:{
          options:{baseURL:base + '/v1', apiKey:'{env:OPENAI_API_KEY}'},
          models:{[config.model]:{name:config.model}}
        }}
      })
    });
  } else throw Error('不支持的 Agent');
  return {args:agentArgs, env};
}
function openAgentInput(path) {
  const fd = fs.openSync(path, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  try {
    const stat = fs.fstatSync(fd);
    if (!stat.isFile() || stat.size > MAX_BODY) throw Error('不支持的 Agent 输入文件');
    return fd;
  } catch (error) { fs.closeSync(fd); throw error; }
}
async function main() {
  const config = JSON.parse(process.env.MOBBY_GATEWAY_CONFIG);
  delete process.env.MOBBY_GATEWAY_CONFIG;
  const [mode, executable, ...args] = process.argv.slice(2);
  const bridge = await createBridge(config);
  let launch;
  let input;
  let child;
  try {
    launch = agentLaunch(mode, args, config, process.env, bridge);
    if (process.env.MOBBY_AGENT_INPUT_FILE) input = openAgentInput(process.env.MOBBY_AGENT_INPUT_FILE);
    child = spawn(executable, launch.args, {env:launch.env, stdio:input === undefined ? 'inherit' : [input, 1, 2]});
  } catch (error) { bridge.close(); throw error; }
  finally { if (input !== undefined) fs.closeSync(input); }
  child.on('error', () => { console.error('无法启动 Agent'); bridge.close(); process.exitCode=1; });
  child.on('exit', code => { bridge.close(); process.exitCode=code ?? 1; });
  for (const signal of ['SIGTERM','SIGINT']) process.on(signal, () => { child.kill(signal); bridge.close(); });
}
module.exports = {openAgentInput, endpoint, createBridge, agentLaunch};
if (require.main === module) main().catch(() => { console.error('无法启动本地桥接，请检查 Agent 协议与网关配置'); process.exitCode=1; });
