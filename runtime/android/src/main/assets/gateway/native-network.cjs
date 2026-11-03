'use strict';
// A transport tunnel, not a model gateway: native CLIs retain OAuth, refresh,
// TLS verification and their exact wire protocol. Android Node resolves DNS.
const http = require('node:http');
const net = require('node:net');
const {randomBytes} = require('node:crypto');

async function createNativeProxy({connect = net.connect} = {}) {
  const token = randomBytes(32).toString('hex');
  const authorization = 'Basic ' + Buffer.from('mobby:' + token).toString('base64');
  const sockets = new Set();
  const server = http.createServer((req, res) => { res.writeHead(405); res.end(); });
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  server.on('connect', (req, client, head) => {
    if (req.headers['proxy-authorization'] !== authorization) {
      client.end('HTTP/1.1 407 Proxy Authentication Required\r\n\r\n'); return;
    }
    let address;
    try { address = new URL('https://' + req.url); } catch (_) { client.destroy(); return; }
    if (address.username || address.password || address.pathname !== '/' || address.search || address.hash ||
        address.port && address.port !== '443' || !address.hostname || req.url.includes('/')) {
      client.end('HTTP/1.1 403 Forbidden\r\n\r\n'); return;
    }
    const upstream = connect({host:address.hostname.replace(/^\[|\]$/g, ''), port:443});
    sockets.add(upstream); upstream.on('close', () => sockets.delete(upstream));
    const close = () => { client.destroy(); upstream.destroy(); };
    upstream.on('error', close); client.on('error', close);
    client.on('close', () => upstream.destroy()); upstream.on('close', () => client.destroy());
    upstream.setTimeout(300000, close); client.setTimeout(300000, close);
    upstream.on('connect', () => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      client.pipe(upstream); upstream.pipe(client);
    });
  });
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  return {url:`http://mobby:${token}@127.0.0.1:${server.address().port}`,
    close() { for (const socket of sockets) socket.destroy(); server.close(); }};
}

function cleanNativeEnvironment(environment) {
  const env = {...environment};
  for (const name of Object.keys(env)) if (/^(?:MOBBY_(?:GATEWAY|PI|NATIVE)|OPENAI_|ANTHROPIC_|CLAUDE_CODE_OAUTH_TOKEN|OPENCODE_CONFIG)/.test(name)) delete env[name];
  return env;
}

async function nativeLaunch(mode, args, config, environment) {
  const proxy = await createNativeProxy();
  const env = {...cleanNativeEnvironment(environment), HTTPS_PROXY:proxy.url, HTTP_PROXY:proxy.url,
    https_proxy:proxy.url, http_proxy:proxy.url, NO_PROXY:'127.0.0.1,localhost,::1', no_proxy:'127.0.0.1,localhost,::1'};
  const launchArgs = [...args];
  if (mode === 'CODEX') launchArgs.unshift('-c', 'model_provider="openai"', '-c', 'cli_auth_credentials_store="file"',
    '-c', `model=${JSON.stringify(config.model)}`);
  else if (mode === 'PI') {
    const provider = launchArgs.indexOf('--provider');
    if (provider < 0) { proxy.close(); throw Error('Missing native Pi provider'); }
    launchArgs[provider+1] = config.provider;
    launchArgs.push('--no-extensions', '--no-prompt-templates', '--no-themes');
    env.PI_SKIP_VERSION_CHECK = '1';
  } else if (mode === 'CLAUDE') {
    env.CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = '1';
    launchArgs.unshift('--model', config.model);
  } else if (mode === 'OPEN_CODE') {
    env.MOBBY_NATIVE_PROVIDER = config.provider;
    env.OPENCODE_DISABLE_AUTOUPDATE = '1';
    // Built-in OAuth provider plugins must remain enabled in native mode.
    delete env.OPENCODE_DISABLE_DEFAULT_PLUGINS;
    env.OPENCODE_CONFIG_CONTENT = JSON.stringify({enabled_providers:[config.provider]});
  } else { proxy.close(); throw Error('Unsupported native agent'); }
  return {args:launchArgs, env, proxy};
}
module.exports = {createNativeProxy, cleanNativeEnvironment, nativeLaunch};
