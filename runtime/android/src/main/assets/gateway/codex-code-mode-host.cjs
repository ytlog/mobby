'use strict';
// Codex runs model-written JavaScript (code mode) in the separate codex-code-mode-host program.
// Codex 0.155.1 looks for that program only beside its own executable. On Android that is the APK
// native-library directory, which holds only lib*.so files. The bridge therefore starts the packaged
// host on a loopback port and passes the address to `codex app-server --code-mode-host`.
const path = require('node:path');
const {spawn} = require('node:child_process');
const {GatewayStrings, GatewayError} = require('./gateway-strings.cjs');
const strings = new GatewayStrings();
const HOST_URL = /^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})$/;
const STARTUP_TIMEOUT_MS = 30000;
const DIAGNOSTIC_BYTES = 2048;
// Codex global options that take a separate value; the first other positional argument is the subcommand.
const VALUE_OPTIONS = new Set(['-c', '--config', '-s', '--sandbox', '-a', '--ask-for-approval', '-m', '--model',
  '-p', '--profile', '-C', '--cd', '--enable', '--disable', '--add-dir', '-i', '--image', '--local-provider']);

function needsCodeModeHost(mode, args) {
  if (mode !== 'CODEX') return false;
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (VALUE_OPTIONS.has(arg)) { index++; continue; }
    if (arg.startsWith('-')) continue;
    return arg === 'app-server';
  }
  return false;
}

function hostProgram(environment) {
  const prefix = environment.PREFIX;
  if (!prefix || !path.isAbsolute(prefix)) throw new GatewayError(strings.codeModeHostFailed('PREFIX'));
  return path.join(prefix, 'bin', 'codex-code-mode-host');
}

/** Options after `app-server` belong to that subcommand. */
function withCodeModeHost(args, url) {
  if (!HOST_URL.test(url)) throw new GatewayError(strings.codeModeHostFailed(url));
  return [...args, '--code-mode-host', url];
}

function startCodeModeHost(program, {env, timeoutMs = STARTUP_TIMEOUT_MS} = {}) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let stdout = '';
    let diagnostic = '';
    const child = spawn(program, ['--listen', 'grpc://127.0.0.1:0'], {env, stdio:['ignore', 'pipe', 'pipe']});
    const close = () => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGTERM'); };
    const fail = detail => {
      if (settled) return;
      settled = true; clearTimeout(timer); close();
      const tail = diagnostic.trim().split('\n').pop();
      reject(new GatewayError(strings.codeModeHostFailed(tail ? detail + ': ' + tail : detail)));
    };
    const timer = setTimeout(() => fail('timeout'), timeoutMs);
    // Keep draining stderr so host logs never block it and never mix into the Agent stream.
    child.stderr.on('data', chunk => { diagnostic = (diagnostic + chunk).slice(-DIAGNOSTIC_BYTES); });
    child.stdout.on('data', chunk => {
      if (settled) return;
      stdout += chunk;
      const newline = stdout.indexOf('\n');
      if (newline < 0) { if (stdout.length > DIAGNOSTIC_BYTES) fail('invalid address'); return; }
      const url = stdout.slice(0, newline).trim();
      if (!HOST_URL.test(url)) { fail('invalid address ' + url.slice(0, 80)); return; }
      settled = true; clearTimeout(timer);
      resolve({url, child, close});
    });
    child.on('error', error => fail(error.code || error.message));
    child.on('exit', (code, signal) => fail(signal ? 'signal ' + signal : 'exit ' + code));
  });
}

module.exports = {needsCodeModeHost, hostProgram, withCodeModeHost, startCodeModeHost};
