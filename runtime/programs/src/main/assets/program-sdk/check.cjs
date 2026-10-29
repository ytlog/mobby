'use strict';
// Parses a program's scripts without running them: `check.cjs <dir> <relative paths...>` prints a JSON array
// of {path, line, message} for each script that does not parse.
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const CJS_PARAMS = ['exports', 'require', 'module', '__filename', '__dirname'];

class ParseError extends Error {
  constructor(message, line) { super(message); this.line = line; }
}

function asCommonJs(code, filename) { vm.compileFunction(code, CJS_PARAMS, { filename }); }
function asClassic(code, filename) { new vm.Script(code, { filename }); }

/** vm parses modules only behind an experimental flag and without a location, so Node itself checks the module from stdin. */
function asModule(code) {
  const result = spawnSync(process.execPath, ['--input-type=module', '--check'], { input: code, encoding: 'utf8' });
  if (result.status === 0) return;
  const lines = String(result.stderr).split('\n');
  const at = lines[0].match(/^\[stdin\]:(\d+)/);
  throw new ParseError(lines.find((l) => /^\w*Error: /.test(l)) || lines[0], at ? Number(at[1]) : null);
}

function parseError(attempt, code, filename) {
  try { attempt(code, filename); return null; } catch (error) { return error; }
}

/** The first error for a script, or null. A plain .js may be CommonJS or a module, and a page script may be classic. */
function check(relative, code, filename) {
  if (relative.endsWith('.cjs')) return parseError(asCommonJs, code, filename);
  if (relative.endsWith('.mjs')) return parseError(asModule, code, filename);
  const page = relative.startsWith('ui/');
  const preferred = page ? asModule : asCommonJs;
  const first = parseError(preferred, code, filename);
  if (!first) return null;
  const others = page ? [asClassic] : [asModule];
  return others.some((other) => !parseError(other, code, filename)) ? null : first;
}

/** V8 puts the location on the first stack line as `<filename>:<line>`. */
function line(error, filename) {
  if (error instanceof ParseError) return error.line;
  const match = String(error.stack || '').split('\n')[0].match(/:(\d+)$/);
  return match && error.stack.startsWith(filename) ? Number(match[1]) : null;
}

try {
  const [dir, ...files] = process.argv.slice(2);
  const problems = [];
  for (const relative of files) {
    const filename = path.join(dir, relative);
    const error = check(relative, fs.readFileSync(filename, 'utf8'), filename);
    if (error) problems.push({ path: relative, line: line(error, filename), message: error instanceof ParseError ? error.message : `${error.name}: ${error.message}` });
  }
  process.stdout.write(JSON.stringify(problems));
} catch (error) {
  process.stderr.write(`check: ${error && error.stack || error}\n`);
  process.exit(2);
}
