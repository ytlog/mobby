'use strict';
// CLI-native Messages/Responses -> selected gateway. No external npm dependencies.
const http = require('node:http');
const {randomUUID, randomBytes, createHash} = require('node:crypto');
const {spawn} = require('node:child_process');
const {Readable} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const MAX_BODY = 16 * 1024 * 1024;
const id = prefix => prefix + randomUUID().replaceAll('-', '');
const toolAlias = (namespace, name) => namespace ? 'ns_' + createHash('sha256').update(namespace + '\0' + name).digest('hex').slice(0, 24) : name;
const text = value => {
  if (typeof value === 'string') return value;
  if (!value) return '';
  if (!Array.isArray(value)) throw Error('不支持的内容格式');
  return value.map(b => {
    if (['text', 'input_text', 'output_text'].includes(b.type)) return b.text || '';
    throw Error(`跨协议暂不支持内容类型：${b.type}`);
  }).join('\n');
};
// The canonical image block uses Chat's shape. Only public HTTP(S) references or
// inline image bytes are translated; provider-owned file IDs cannot cross gateways.
function imageBlock(block, source) {
  let url, detail;
  if (block.transformations) throw Error('跨协议不支持图片 transformations');
  if (source === 'messages') {
    const image = block.source;
    if (image?.type === 'base64') {
      if (!['image/png','image/jpeg','image/webp','image/gif'].includes(image.media_type) ||
          typeof image.data !== 'string' || !image.data || !/^[A-Za-z0-9+/]+={0,2}$/.test(image.data) ||
          Buffer.from(image.data,'base64').toString('base64') !== image.data) throw Error('跨协议图片编码无效');
      url = `data:${image.media_type};base64,${image.data}`;
    } else if (image?.type === 'url') url = image.url;
    else throw Error('跨协议不支持此图片来源');
  } else {
    if (block.file_id) throw Error('跨协议不能引用提供商的图片 file_id');
    url = block.image_url; detail = block.detail;
    if (detail != null && !['auto','low','high','original'].includes(detail)) throw Error('跨协议图片 detail 无效');
  }
  if (typeof url !== 'string') throw Error('跨协议图片缺少 URL');
  const data = /^data:(image\/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/]+={0,2})$/.exec(url);
  if (data) {
    if (Buffer.from(data[2],'base64').toString('base64') !== data[2]) throw Error('跨协议图片编码无效');
  } else {
    let parsed; try { parsed = new URL(url); } catch { throw Error('跨协议图片 URL 无效'); }
    if (!['http:','https:'].includes(parsed.protocol) || parsed.username || parsed.password) throw Error('跨协议图片 URL 不受支持');
  }
  return {type:'image_url',image_url:{url,...(detail ? {detail} : {})}};
}
function inputContent(value, source) {
  if (typeof value === 'string' || value == null) return value || '';
  if (!Array.isArray(value)) throw Error('跨协议内容格式无效');
  const parts = value.map(block => {
    if (['text','input_text','output_text'].includes(block.type)) return {type:'text',text:block.text || ''};
    if (block.type === (source === 'messages' ? 'image' : 'input_image')) return imageBlock(block, source);
    throw Error(`跨协议暂不支持内容类型：${block.type}`);
  });
  return parts.some(block => block.type === 'image_url') ? parts : parts.map(block => block.text).join('\n');
}
function contentFor(value, target) {
  if (!Array.isArray(value)) return value;
  return value.map(block => {
    if (block.type === 'text') return target === 'responses' ? {type:'input_text',text:block.text} : block;
    if (block.type !== 'image_url') throw Error('跨协议内容类型无效');
    const {url,detail} = block.image_url;
    if (target === 'responses') return {type:'input_image',image_url:url,...(detail ? {detail} : {})};
    if (target === 'chat') return block;
    if (detail && detail !== 'auto') throw Error('跨协议 Messages 无法表示显式图片 detail，请使用原生协议');
    const data = /^data:(image\/[^;]+);base64,(.+)$/.exec(url);
    return {type:'image',source:data ? {type:'base64',media_type:data[1],data:data[2]} : {type:'url',url}};
  });
}
function endpoint(config) {
  if (!['chat', 'responses', 'messages'].includes(config.protocol)) throw Error('网关协议无效');
  const url = new URL(config.endpoint);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw Error('网关 URL 无效');
  let path = url.pathname.replace(/\/+$/, '');
  path = path.replace(/\/(chat\/completions|responses|messages)$/, '');
  if (!path) path = '/v1';
  url.pathname = path + {chat:'/chat/completions', responses:'/responses', messages:'/messages'}[config.protocol];
  return url.toString();
}
function canonical(body, source) {
  const messages = [], tools = [], custom = new Set(), aliases = new Map();
  if (source === 'messages') {
    if (body.system) messages.push({role:'system', content:text(body.system)});
    for (const m of body.messages || []) {
      if (typeof m.content === 'string') { messages.push({...m}); continue; }
      let content = [], calls = [];
      const flush = () => {
        if (content.length || calls.length) {
          if (content.some(b=>b.type==='image_url') && m.role !== 'user') throw Error('跨协议图片只支持用户消息或工具结果');
          const packed = content.some(b=>b.type==='image_url') ? content : content.map(b=>b.text).join('');
          messages.push({role:m.role, content:packed || null, ...(calls.length ? {tool_calls:calls} : {})});
        }
        content=[]; calls=[];
      };
      for (const b of m.content || []) {
        if (b.type === 'text') content.push({type:'text',text:b.text});
        else if (b.type === 'image') content.push(imageBlock(b,'messages'));
        else if (b.type === 'tool_use') calls.push({id:b.id, type:'function', function:{name:b.name, arguments:JSON.stringify(b.input)}});
        else if (b.type === 'tool_result') {
          flush(); let result = inputContent(b.content,'messages');
          if (b.is_error) result = Array.isArray(result) ? [{type:'text',text:'Tool error: '},...result] : 'Tool error: ' + result;
          messages.push({role:'tool', tool_call_id:b.tool_use_id, content:result});
        }
        else if (b.type !== 'thinking' && b.type !== 'redacted_thinking') throw Error(`跨协议暂不支持内容类型：${b.type}`);
      }
      flush();
    }
    for (const t of body.tools || []) {
      if (t.type && t.type !== 'custom') throw Error(`跨协议暂不支持工具类型：${t.type}`);
      tools.push({type:'function', function:{name:t.name, description:t.description || '', parameters:t.input_schema}});
    }
  } else {
    if (body.previous_response_id) throw Error('跨协议需要完整会话，不能使用 previous_response_id');
    if (body.instructions) messages.push({role:'system', content:body.instructions});
    function addTool(t, namespace) {
      if (t.type === 'namespace') {
        if (namespace) throw Error('跨协议暂不支持嵌套工具命名空间');
        for (const child of t.tools || []) addTool(child, t.name);
        return;
      }
      const name = toolAlias(namespace, t.name);
      if (namespace) aliases.set(name, {namespace, name:t.name});
      const description = (namespace ? `[${namespace}.${t.name}] ` : '') + (t.description || '');
      if (t.type === 'function') tools.push({type:'function', function:{name, description, parameters:t.parameters}});
      else if (t.type === 'custom') {
        custom.add(name);
        tools.push({type:'function', function:{name, description, parameters:{type:'object', properties:{input:{type:'string', description:'Raw tool input'}}, required:['input'], additionalProperties:false}}});
      } else throw Error(`跨协议暂不支持工具类型：${t.type}`);
    }
    for (const t of body.tools || []) addTool(t);
    const input = typeof body.input === 'string' ? [{role:'user', content:body.input}] : body.input || [];
    for (const item of input) {
      if (item.type === 'function_call' || item.type === 'custom_tool_call') {
        const call = {id:item.call_id, type:'function', function:{name:toolAlias(item.namespace, item.name),
          arguments:item.type === 'custom_tool_call' ? JSON.stringify({input:item.input}) : item.arguments}};
        const previous = messages.at(-1);
        // Chat requires all parallel calls in the assistant turn before their results.
        if (previous?.role === 'assistant') (previous.tool_calls ||= []).push(call);
        else messages.push({role:'assistant', content:null, tool_calls:[call]});
      } else if (item.type === 'function_call_output' || item.type === 'custom_tool_call_output') {
        messages.push({role:'tool', tool_call_id:item.call_id, content:inputContent(item.output,'responses')});
      } else if (item.type === 'reasoning') { /* Provider-specific hidden reasoning cannot be transferred. */ }
      else if (!item.type || item.type === 'message') {
        const content = inputContent(item.content,'responses');
        if (Array.isArray(content) && item.role !== 'user') throw Error('跨协议图片只支持用户消息或工具结果');
        messages.push({role:item.role === 'developer' ? 'system' : item.role, content});
      }
      else throw Error(`跨协议暂不支持输入类型：${item.type}`);
    }
  }
  let choice = body.tool_choice;
  if (source === 'messages' && choice) choice = choice.type === 'tool' ? {type:'function', function:{name:choice.name}} : choice.type === 'any' ? 'required' : choice.type;
  else if (choice && typeof choice === 'object') {
    if (['function','custom'].includes(choice.type)) choice = {type:'function', function:{name:toolAlias(choice.namespace, choice.name)}};
    else throw Error(`跨协议暂不支持工具选择：${choice.type}`);
  }
  return {messages, tools, custom, aliases, choice, max:body.max_tokens || body.max_output_tokens, temperature:body.temperature};
}
function encode(c, target, model) {
  const base = {model, stream:false};
  if (c.temperature != null) base.temperature = c.temperature;
  if (target === 'chat' && c.messages.some(m=>m.role==='tool' && Array.isArray(m.content))) throw Error('跨协议 Chat 无法表示工具结果中的图片，请使用原生协议');
  if (target === 'chat') return {...base, messages:c.messages, ...(c.tools.length ? {tools:c.tools} : {}), ...(c.choice ? {tool_choice:c.choice} : {}), ...(c.max ? {max_tokens:c.max} : {})};
  if (target === 'responses') {
    const input = [];
    for (const m of c.messages) {
      if (m.role === 'tool') input.push({type:'function_call_output', call_id:m.tool_call_id, output:contentFor(m.content,'responses')});
      else {
        if (m.content) input.push({role:m.role, content:contentFor(m.content,'responses')});
        for (const t of m.tool_calls || []) input.push({type:'function_call', call_id:t.id, name:t.function.name, arguments:t.function.arguments});
      }
    }
    let choice = c.choice;
    if (choice && typeof choice === 'object') choice = {type:'function', name:choice.function.name};
    return {...base, input, store:false, ...(c.tools.length ? {tools:c.tools.map(t => ({type:'function', ...t.function}))} : {}), ...(choice ? {tool_choice:choice} : {}), ...(c.max ? {max_output_tokens:c.max} : {})};
  }
  const system = [], messages = [];
  for (const m of c.messages) {
    if (m.role === 'system') { system.push(m.content); continue; }
    const role = m.role === 'tool' ? 'user' : m.role;
    const content = [];
    if (m.role === 'tool') content.push({type:'tool_result', tool_use_id:m.tool_call_id, content:contentFor(m.content,'messages')});
    else {
      if (Array.isArray(m.content)) content.push(...contentFor(m.content,'messages'));
      else if (m.content) content.push({type:'text', text:m.content});
      for (const t of m.tool_calls || []) content.push({type:'tool_use', id:t.id, name:t.function.name, input:JSON.parse(t.function.arguments)});
    }
    if (!content.length) continue;
    if (messages.at(-1)?.role === role) messages.at(-1).content.push(...content);
    else messages.push({role, content});
  }
  let choice = c.choice;
  if (typeof choice === 'string') choice = {type:choice === 'required' ? 'any' : choice};
  else if (choice) choice = {type:'tool', name:choice.function.name};
  return {...base, max_tokens:c.max || 8192, system:system.join('\n'), messages,
    ...(c.tools.length ? {tools:c.tools.map(t => ({name:t.function.name, description:t.function.description, input_schema:t.function.parameters}))} : {}), ...(choice ? {tool_choice:choice} : {})};
}
function decode(body, protocol) {
  if (body.error) throw Error('网关返回 API 错误');
  let content = '', calls = [], input = 0, output = 0, limited = false;
  if (protocol === 'chat') {
    const choice = body.choices?.[0];
    if (!choice?.message) throw Error('网关未返回 Chat Completions message');
    if (choice.finish_reason === 'content_filter') throw Error('网关内容过滤拒绝');
    content = text(choice.message.content) || choice.message.refusal || '';
    calls = choice.message.tool_calls || [];
    input = body.usage?.prompt_tokens || 0; output = body.usage?.completion_tokens || 0;
    limited = choice.finish_reason === 'length';
  } else if (protocol === 'messages') {
    if (!Array.isArray(body.content)) throw Error('网关未返回 Messages content');
    for (const b of body.content) {
      if (b.type === 'text') content += b.text;
      else if (b.type === 'tool_use') calls.push({id:b.id, type:'function', function:{name:b.name, arguments:JSON.stringify(b.input)}});
      else if (!['thinking', 'redacted_thinking'].includes(b.type)) throw Error(`不支持的网关响应类型：${b.type}`);
    }
    input = body.usage?.input_tokens || 0; output = body.usage?.output_tokens || 0;
    limited = body.stop_reason === 'max_tokens';
  } else {
    if (!Array.isArray(body.output) || ['failed','cancelled','queued','in_progress'].includes(body.status)) throw Error('网关未返回已完成的 Responses output');
    for (const b of body.output) {
      if (b.type === 'message') content += (b.content || []).map(x => x.type === 'refusal' ? x.refusal : text([x])).join('');
      else if (b.type === 'function_call') calls.push({id:b.call_id, type:'function', function:{name:b.name, arguments:b.arguments}});
      else if (b.type !== 'reasoning') throw Error(`不支持的网关响应类型：${b.type}`);
    }
    input = body.usage?.input_tokens || 0; output = body.usage?.output_tokens || 0;
    limited = body.status === 'incomplete';
  }
  if (!content && !calls.length) throw Error('网关返回空内容');
  for (const call of calls) { call.id ||= id('call_'); JSON.parse(call.function.arguments); }
  return {content, calls, input, output, limited};
}
function nativeResponse(c, source, model, custom = new Set(), aliases = new Map()) {
  if (source === 'messages') return {id:id('msg_'), type:'message', role:'assistant', model,
    content:[...(c.content ? [{type:'text', text:c.content}] : []), ...c.calls.map(t => ({type:'tool_use', id:t.id, name:t.function.name, input:JSON.parse(t.function.arguments)}))],
    stop_reason:c.limited ? 'max_tokens' : c.calls.length ? 'tool_use' : 'end_turn', stop_sequence:null, usage:{input_tokens:c.input, output_tokens:c.output}};
  return {id:id('resp_'), object:'response', created_at:Math.floor(Date.now()/1000), model, status:c.limited ? 'incomplete' : 'completed', error:null,
    incomplete_details:c.limited ? {reason:'max_output_tokens'} : null,
    output:[...(c.content ? [{id:id('msg_'), type:'message', role:'assistant', status:'completed', content:[{type:'output_text', text:c.content, annotations:[]}]}] : []),
      ...c.calls.map(t => ({...(custom.has(t.function.name) ? {id:id('ct_'), type:'custom_tool_call', status:'completed', call_id:t.id, name:t.function.name, input:JSON.parse(t.function.arguments).input} :
        {id:id('fc_'), type:'function_call', status:'completed', call_id:t.id, name:t.function.name, arguments:t.function.arguments}), ...aliases.get(t.function.name)}))],
    usage:{input_tokens:c.input, output_tokens:c.output, total_tokens:c.input+c.output, input_tokens_details:{cached_tokens:0}, output_tokens_details:{reasoning_tokens:0}}};
}
function sendNative(res, result, source, stream) {
  if (!stream) { res.writeHead(200, {'content-type':'application/json'}); return res.end(JSON.stringify(result)); }
  res.writeHead(200, {'content-type':'text/event-stream', 'cache-control':'no-cache'});
  let sequence = 0;
  const emit = (type, data) => res.write(`event: ${type}\ndata: ${JSON.stringify({type, ...data, ...(source === 'responses' ? {sequence_number:sequence++} : {})})}\n\n`);
  if (source === 'messages') {
    emit('message_start', {message:{...result, content:[], stop_reason:null, usage:{...result.usage, output_tokens:0}}});
    result.content.forEach((b, index) => {
      emit('content_block_start', {index, content_block:b.type === 'text' ? {type:'text', text:''} : {...b, input:{}}});
      emit('content_block_delta', {index, delta:b.type === 'text' ? {type:'text_delta', text:b.text} : {type:'input_json_delta', partial_json:JSON.stringify(b.input)}});
      emit('content_block_stop', {index});
    });
    emit('message_delta', {delta:{stop_reason:result.stop_reason, stop_sequence:null}, usage:{output_tokens:result.usage.output_tokens}});
    emit('message_stop', {});
  } else {
    emit('response.created', {response:{...result, output:[], status:'in_progress'}});
    emit('response.in_progress', {response:{...result, output:[], status:'in_progress'}});
    result.output.forEach((item, output_index) => {
      const common = {item_id:item.id, output_index};
      emit('response.output_item.added', {output_index, item:{...item, status:'in_progress', ...(item.type === 'message' ? {content:[]} : item.type === 'function_call' ? {arguments:''} : {input:''})}});
      if (item.type === 'message') {
        const part = item.content[0];
        emit('response.content_part.added', {...common, content_index:0, part:{...part, text:''}});
        emit('response.output_text.delta', {...common, content_index:0, delta:part.text});
        emit('response.output_text.done', {...common, content_index:0, text:part.text});
        emit('response.content_part.done', {...common, content_index:0, part});
      } else if (item.type === 'function_call') {
        emit('response.function_call_arguments.delta', {...common, delta:item.arguments});
        emit('response.function_call_arguments.done', {...common, arguments:item.arguments});
      } else {
        emit('response.custom_tool_call_input.delta', {...common, delta:item.input});
        emit('response.custom_tool_call_input.done', {...common, input:item.input});
      }
      emit('response.output_item.done', {output_index, item});
    });
    emit(result.status === 'incomplete' ? 'response.incomplete' : 'response.completed', {response:result});
  }
  res.end();
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
      const path = new URL(req.url, 'http://localhost').pathname;
      const source = path === '/v1/messages' ? 'messages' : path === '/v1/responses' ? 'responses' : null;
      if (req.method !== 'POST' || !source) { res.writeHead(404); res.end(JSON.stringify({error:{message:'不支持的本地网关路径'}})); return; }
      const body = JSON.parse(await readBounded(req));
      const same = source === config.protocol;
      let c, payload;
      try {
        c = same ? null : canonical(body, source);
        payload = same ? {...body, model:config.model} : encode(c, config.protocol, config.model);
      } catch (error) {
        // Unsupported input is deterministic: retries cannot make it representable.
        error.invalidInput = true;
        throw error;
      }
      const headers = {'content-type':'application/json'};
      if (config.key) {
        headers.authorization = `Bearer ${config.key}`;
        if (config.protocol === 'messages') headers['x-api-key'] = config.key;
      }
      if (config.protocol === 'messages') {
        headers['anthropic-version'] = req.headers['anthropic-version'] || '2023-06-01';
        if (same && req.headers['anthropic-beta']) headers['anthropic-beta'] = req.headers['anthropic-beta'];
      }
      const response = await fetch(upstream, {method:'POST', headers, body:JSON.stringify(payload), redirect:'error', signal:controller.signal});
      if (!response.ok) {
        await response.body?.cancel();
        res.writeHead(response.status, {'content-type':'application/json'});
        res.end(JSON.stringify({type:'error', error:{type:'api_error', message:`网关 HTTP ${response.status}，请检查地址、协议、模型和密钥`}}));
      } else if (same) {
        res.writeHead(200, {'content-type':response.headers.get('content-type') || 'application/json'});
        await pipeline(Readable.fromWeb(response.body), res, {signal:controller.signal});
      } else {
        const decoded = decode(JSON.parse(await readBounded(response.body)), config.protocol);
        sendNative(res, nativeResponse(decoded, source, config.model, c.custom, c.aliases), source, body.stream);
      }
    } catch (error) {
      // Once SSE headers/data are sent, an HTTP/JSON error would corrupt the stream.
      if (res.headersSent || res.destroyed) { res.destroy(); return; }
      if (!res.headersSent) res.writeHead(error.invalidInput ? 400 : 502, {'content-type':'application/json'});
      // Only local validation errors are useful; network errors may contain URLs or credentials.
      const message = /^(跨协议|不支持|网关|请输入)/.test(error.message) ? error.message : '网关请求失败，请检查网络与协议配置';
      res.end(JSON.stringify({type:'error', error:{type:error.invalidInput ? 'invalid_request_error' : 'api_error', message}}));
    } finally { clearTimeout(timer); controllers.delete(controller); }
  });
  server.requestTimeout = 300000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {url:`http://127.0.0.1:${server.address().port}`, token, close:() => { for (const c of controllers) c.abort(); server.closeAllConnections(); server.close(); }};
}
// The CLI speaks its native protocol directly whenever URL/auth allow it.
function nativeBase(mode, config) {
  const protocol = mode === 'CLAUDE' ? 'messages' : mode === 'CODEX' ? 'responses' : null;
  if (!protocol) throw Error('不支持的 Agent');
  if (config.protocol !== protocol || !config.key) return null;
  const url = endpoint(config);
  if (mode === 'CLAUDE') return url.endsWith('/v1/messages') ? url.slice(0, -'/v1/messages'.length) : null;
  return url.slice(0, -'/responses'.length);
}
function agentLaunch(mode, args, config, environment, bridge = null) {
  const base = bridge?.url || nativeBase(mode, config);
  if (!base) throw Error('网关需要协议或路径适配');
  const token = bridge?.token || config.key;
  const env = {...environment};
  delete env.MOBBY_GATEWAY_CONFIG;
  const agentArgs = [...args];
  if (mode === 'CLAUDE') {
    Object.assign(env, {ANTHROPIC_BASE_URL:base, ANTHROPIC_AUTH_TOKEN:token, ANTHROPIC_API_KEY:'', ANTHROPIC_MODEL:config.model,
      ANTHROPIC_DEFAULT_OPUS_MODEL:config.model, ANTHROPIC_DEFAULT_SONNET_MODEL:config.model, ANTHROPIC_DEFAULT_HAIKU_MODEL:config.model,
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC:'1'});
  } else if (mode === 'CODEX') {
    env.MOBBY_GATEWAY_TOKEN = token;
    const options = [
      'model_provider="mobby"', `model=${JSON.stringify(config.model)}`,
      'model_providers.mobby.name="mobby Gateway"', `model_providers.mobby.base_url=${JSON.stringify(bridge ? base + '/v1' : base)}`,
      'model_providers.mobby.env_key="MOBBY_GATEWAY_TOKEN"', 'model_providers.mobby.wire_api="responses"',
      'model_providers.mobby.requires_openai_auth=false', 'model_providers.mobby.supports_websockets=false'
    ];
    if (config.protocol !== 'responses') options.push(
      'model_auto_compact_token_limit=100000000', 'model_supports_reasoning_summaries=false', 'web_search="disabled"'
    );
    agentArgs.unshift(...options.flatMap(value => ['-c', value]));
  } else throw Error('不支持的 Agent');
  return {args:agentArgs, env};
}
async function main() {
  const config = JSON.parse(process.env.MOBBY_GATEWAY_CONFIG);
  delete process.env.MOBBY_GATEWAY_CONFIG;
  const [mode, executable, ...args] = process.argv.slice(2);
  const bridge = nativeBase(mode, config) === null ? await createBridge(config) : null;
  const launch = agentLaunch(mode, args, config, process.env, bridge);
  const child = spawn(executable, launch.args, {env:launch.env, stdio:'inherit'});
  child.on('error', () => { console.error('无法启动 Agent'); bridge?.close(); process.exitCode=1; });
  child.on('exit', code => { bridge?.close(); process.exitCode=code ?? 1; });
  for (const signal of ['SIGTERM','SIGINT']) process.on(signal, () => { child.kill(signal); bridge?.close(); });
}
module.exports = {endpoint, canonical, encode, decode, nativeResponse, sendNative, createBridge, nativeBase, agentLaunch};
if (require.main === module) main().catch(() => { console.error('无法启动网关，请检查配置'); process.exitCode=1; });
