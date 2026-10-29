package com.github.ytlog.mobby.android.programs.dev

import com.github.ytlog.mobby.android.programs.capability.Availability
import com.github.ytlog.mobby.android.programs.capability.CapabilityDescriptor

/** The catalog an AI reads before writing a program; generated from the registry so it lists only what exists. */
internal object DevelopmentGuide {
    fun render(capabilities: List<Pair<CapabilityDescriptor, Availability>>): String = buildString {
        append(HEADER)
        append("\n## Capabilities on this phone\n\n")
        append("Request each capability you use in manifest `permissions` (except those marked implicit). ")
        append("The user grants them in mobby; a capability may be unavailable on this phone.\n\n")
        capabilities.forEach { (d, availability) ->
            append("### ${d.id}\n")
            append("- kind: ${d.kind.name.lowercase()}; scope: ${d.scope.name.lowercase()}; effects: ${d.sideEffects.joinToString { it.name.lowercase() }.ifEmpty { "none" }}\n")
            append("- callable from: ${d.surfaces.joinToString { it.name.lowercase() }}${if (d.implicit) "; implicit (no request needed)" else ""}\n")
            append("- on this phone: ${if (availability is Availability.Unavailable) "unavailable (${availability.reason.name.lowercase()})" else "available"}\n")
            append("- input schema: `${d.inputSchema}`\n\n")
        }
    }

    private val HEADER = """
# Writing mobby programs

A program is a folder under `~/workspace/programs/<program-id>/` with a `manifest.json`, optional background
logic (Node, `main.js`) and an optional page (`ui/index.html`). You write it; it then shows up as waiting to install under
"程序开发" (Program development) in the conversation's + menu, where the user installs, grants, configures and enables it in one
confirmation. You cannot do those steps and must not claim the program is running.

## manifest.json (mobby.program/1, unknown fields are rejected)

```json
{
  "schema": "mobby.program/1",
  "id": "price-watch",
  "version": "1.0.0",
  "name": "Price watch",
  "background": "main.js",
  "ui": {"entry": "ui/index.html"},
  "permissions": [
    {"capability": "net.http", "required": true, "scope": {"domains": ["api.example.com"], "methods": ["GET"]}},
    {"capability": "storage.sql", "required": true},
    {"capability": "time.schedule", "required": true},
    {"capability": "notify.show", "required": false}
  ],
  "config": [
    {"key": "threshold", "type": "number", "required": true, "label": "Threshold"},
    {"key": "apiToken", "type": "secret", "required": true, "label": "API token", "domains": ["api.example.com"]}
  ],
  "subscriptions": [
    {"id": "hourly", "event": "time.schedule", "filter": {"every": "PT1H"}, "handler": "onSchedule"}
  ]
}
```

- `id`: lowercase letters, digits and `-`. Config types: string, number, boolean, enum (with `options`), package, secret (with `domains`).
- `time.schedule` filters: `{"every": "PT15M".."P30D"}`, `{"at": "2026-10-24T09:00:00Z"}` or `{"daily": "09:00", "timezone": "Asia/Shanghai"}`, optional `"missed": "skip"|"once"`.
- Domain scopes are exact hosts or `*.suffix`; HTTPS only unless `"insecureHttp": true`.

## Background logic (Node)

```javascript
const mobby = require('mobby');
async function refresh() {
  const { threshold } = await mobby.config.get();
  const response = await mobby.net.fetch('https://api.example.com/price', { headers: { Authorization: 'Bearer {{secret:apiToken}}' } });
  const { price } = await response.json();
  await mobby.sql.execute('CREATE TABLE IF NOT EXISTS price(at INTEGER, value REAL)');
  await mobby.sql.execute('INSERT INTO price VALUES (?, ?)', [Date.now(), price]);
  if (price < threshold) await mobby.notify.show({ title: 'Below threshold', body: String(price), openUi: true });
  await mobby.ui.post({ type: 'updated' });
}
mobby.on('onSchedule', refresh);
mobby.on('ui.message', async (payload) => { if (payload.message.type === 'refresh') await refresh(); });
```

SDK (arguments are positional; only the ones shown in braces take an object):
- `call(capability, args)`, `config.get()` (an object keyed by config key), `log(message, data)`
- `kv.get(key)`, `kv.set(key, value)`, `kv.delete(key)`
- `sql.query(sql, params)`, `sql.execute(sql, params)`, `sql.transaction([{sql, params}])`
- `files.read(path)`, `files.write(path, text)`, `files.list(dir)`, `files.delete(path)`; paths are relative to the program's own files
- `net.fetch(url, {method, headers, body})`, `notify.show({title, body, openUi})`
- `ai.call({system, messages, maxOutputTokens})` with `messages: [{role: 'user' | 'assistant', text}]`, last one from the user;
  returns `{text, model, usage}`. Text only: images, tools and other fields are refused. The user picks the gateway and model
  when installing, and daily calls and tokens are capped (`QUOTA_EXCEEDED`); request `ai.call` in the manifest without a scope.
- `secrets.put(name, value, {domains})`, `secrets.list()`, `secrets.delete(name)`
- `permissions.list()`, `permissions.request(capability, scope)`, `capabilities.list()`
- background only: `on(handler, fn)`, `ui.post(message)`; page only: `ui.send(message)`, `ui.onMessage(fn)`.
`ui.send` resolves once the background run has received the message, not when its handler finishes: report the outcome,
including failures, back with `ui.post` and show it from `ui.onMessage`. A run in which any handler threw or was missing ends
with exit code 1 and its error is in the program log.
`sql.query` returns `{columns, rows}`: `columns` lists the column names and each row is an array in that order
(`rows[0][0]`, not `rows[0].name`). `sql.execute` returns `{changes}`, plus `lastInsertRowId` for INSERT.
Failures throw `MobbyError` with `code`, `layer`, `reason`, `effect`; never treat an error or `EFFECT_UNKNOWN` as success.
Arguments of the wrong type (an object where a path is expected, params that are not an array) reject with a `TypeError`
that shows the call's real form, before anything is sent.
Secrets are written with `secrets.put` or by the user, and used only as `{{secret:name}}` in headers or query strings.
Use the SDK instead of Node's own fetch, fs, sqlite or child_process: direct use is not governed, recorded or counted.

## Page (WebView)

```html
<script type="module" src="app.js"></script>
```
```javascript
import mobby from '/_mobby/sdk.js';
const { rows } = await mobby.sql.query('SELECT at, value FROM price ORDER BY at DESC LIMIT 50');
document.querySelector('#refresh').onclick = () => mobby.ui.send({ type: 'refresh' });
mobby.ui.onMessage((message) => { if (message.type === 'updated') location.reload(); });
```

A dry run covers background logic only, never the page: catch page failures and show them instead of failing silently.
`validate` parses every script (background and page) without running it and lists those that do not parse. Errors a page
reports to its console (uncaught exceptions, syntax errors, blocked resources, `console.error`) appear in `records` logs as `page_error`.
Pages may load only their own files: no inline scripts or `on…=` handlers, no external scripts, styles or images,
no direct network access (use `mobby.net.fetch`). External links open in the browser after the user confirms.

## Commands

- `validate` checks the folder; fix every problem it lists.
- `dry-run` runs the background logic once with your simulated events; commands and network requests are simulated,
  storage uses throwaway data. A simulated command returns `{simulated: true}`; a simulated `net.fetch` returns a
  response with `response.simulated` true, `status` null and no body, and `text()`/`json()` throw `MobbyError` code
  `SIMULATED`. Check `response.simulated` instead of guessing at a body. A dry run passing does not prove the program
  works on real events.
- Each event is `{"handler": name, "type": event type, "payload": {...}}`. A page's `ui.send(message)` reaches the
  background as the `ui.message` handler, so dry-run every action a page sends, for example
  `{"handler": "ui.message", "payload": {"message": {"type": "export"}}}`.
- The result lists the program's registered `handlers` and those this dry run did `notExercised`; dry-run them too.
  Messages the background sent with `ui.post` are returned in `uiMessages` and are not shown on any open page.
""".trimStart()
}
