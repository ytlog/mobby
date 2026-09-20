'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {once} = require('node:events');
const {endpoint, canonical, encode, decode, nativeResponse, createBridge} = require('../../runtime-android/src/main/assets/gateway/bridge.cjs');
const tool = {name:'read_file', description:'Read file', parameters:{type:'object', properties:{path:{type:'string'}}, required:['path']}};
const call = {id:'call_1', type:'function', function:{name:'read_file', arguments:'{"path":"a.txt"}'}};
const reply = {content:'好', calls:[call], input:10, output:4};
function input(source) {
  return source === 'messages' ? {model:'ignored', stream:true, max_tokens:1000, system:'System', tools:[{name:tool.name, description:tool.description, input_schema:tool.parameters}], messages:[{role:'user', content:'hello'}]} :
    {model:'ignored', stream:true, instructions:'System', tools:[{type:'function', ...tool}], input:[{role:'user', content:'hello'}]};
}
function upstreamReply(target, result = reply) {
  if (target === 'chat') return {choices:[{message:{role:'assistant', content:result.content, tool_calls:result.calls}, finish_reason:result.calls.length ? 'tool_calls' : 'stop'}], usage:{prompt_tokens:10, completion_tokens:4}};
  return nativeResponse(result, target, 'test-model');
}
async function mock(handler) {
  const server = http.createServer(async (req,res) => { const chunks=[]; for await (const chunk of req) chunks.push(chunk); await handler(req,res,JSON.parse(Buffer.concat(chunks).toString())); });
  server.listen(0,'127.0.0.1'); await once(server,'listening');
  return {url:`http://127.0.0.1:${server.address().port}/v1`, close:() => {server.closeAllConnections();server.close();}};
}
test('endpoints normalize root, version prefix, full paths without doubling', () => {
  for (const protocol of ['chat','messages','responses']) {
    const suffix = protocol === 'chat' ? 'chat/completions' : protocol;
    for (const endpointValue of ['https://example.com','https://example.com/v1/','https://example.com/v1/responses']) assert.equal(endpoint({endpoint:endpointValue,protocol}),`https://example.com/v1/${suffix}`);
    assert.equal(endpoint({endpoint:'https://example.com/api/v2',protocol}),`https://example.com/api/v2/${suffix}`);
  }
  assert.throws(() => endpoint({endpoint:'https://key@example.com',protocol:'chat'}));
});
for (const source of ['messages','responses']) for (const target of ['chat','messages','responses']) {
  test(`${source} -> ${target}: authenticated request, model override, text and tool roundtrip`, async () => {
    let received;
    const upstream = await mock((req,res,body) => {
      assert.equal(req.headers.authorization,'Bearer upstream-secret');
      assert.equal(req.url, target === 'chat' ? '/v1/chat/completions' : '/v1/'+target);
      assert.equal(body.model,'configured-model');
      if (target === 'messages') assert.equal(req.headers['x-api-key'],'upstream-secret');
      received=body;
      res.setHeader('content-type','application/json');res.end(JSON.stringify(upstreamReply(target)));
    });
    const bridge = await createBridge({endpoint:upstream.url,protocol:target,model:'configured-model',key:'upstream-secret'});
    try {
      const response = await fetch(bridge.url+'/v1/'+source,{method:'POST', headers:{authorization:'Bearer '+bridge.token}, body:JSON.stringify({...input(source),stream:source!==target})});
      assert.equal(response.status,200);
      const raw=await response.text();
      assert.ok(raw.includes('好')); assert.ok(raw.includes('read_file')); assert.ok(!raw.includes('upstream-secret'));
      if (source!==target) {assert.equal(received.stream,false);assert.ok(raw.includes(source==='messages'?'message_stop':'response.completed'));}
      // A CLI's next turn must retain the call ID and tool result, not lose its association.
      let second;
      if (source==='messages') second={...input(source), messages:[...input(source).messages,{role:'assistant',content:[{type:'tool_use',id:'call_1',name:'read_file',input:{path:'a.txt'}}]},{role:'user',content:[{type:'tool_result',tool_use_id:'call_1',content:'FILE CONTENT'}]}]};
      else second={...input(source), input:[...input(source).input,{type:'function_call',call_id:'call_1',name:'read_file',arguments:call.function.arguments},{type:'function_call_output',call_id:'call_1',output:'FILE CONTENT'}]};
      const converted=encode(canonical(second,source),target,'configured-model');
      assert.ok(JSON.stringify(converted).includes('call_1'));assert.ok(JSON.stringify(converted).includes('FILE CONTENT'));
    } finally {bridge.close();upstream.close();}
  });
}
test('custom Codex tool keeps raw input when converted through a function tool',()=>{
  const c=canonical({input:[],tools:[{type:'custom',name:'apply_patch',description:'Patch'}]},'responses');
  const result=nativeResponse({...reply,calls:[{id:'call_patch',function:{name:'apply_patch',arguments:JSON.stringify({input:'*** Begin Patch\n*** End Patch'})}}]},'responses','model',c.custom);
  assert.equal(result.output[1].type,'custom_tool_call');assert.equal(result.output[1].input,'*** Begin Patch\n*** End Patch');
});
test('unsupported multimodal data and server-only tools fail explicitly',()=>{
  assert.throws(()=>canonical({input:[{role:'user',content:[{type:'input_image',image_url:'secret'}]}]},'responses'),/跨协议/);
  assert.throws(()=>canonical({input:[],tools:[{type:'web_search'}]},'responses'),/工具类型/);
  assert.throws(()=>canonical({previous_response_id:'resp_old',input:[]},'responses'),/完整会话/);
});
test('upstream errors keep status but redact response body; loopback requires random token',async()=>{
  const upstream=await mock((req,res)=>{res.writeHead(401);res.end('upstream-secret');});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'chat',model:'m',key:'upstream-secret'});
  try {
    assert.equal((await fetch(bridge.url+'/v1/responses',{method:'POST',body:'{}'})).status,401);
    const response=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify(input('responses'))});
    assert.equal(response.status,401);assert.ok(!(await response.text()).includes('upstream-secret'));
  } finally {bridge.close();upstream.close();}
});
test('same-protocol SSE is forwarded without transformation',async()=>{
  const stream='event: response.completed\ndata: {"type":"response.completed","response":{"id":"resp_1","output":[]}}\n\n';
  const upstream=await mock((req,res)=>{res.setHeader('content-type','text/event-stream');res.end(stream);});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:''});
  try { const r=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify(input('responses'))});assert.equal(await r.text(),stream);}
  finally {bridge.close();upstream.close();}
});
test('length-limited output stays incomplete',()=>{
  const c=decode({choices:[{message:{content:'partial'},finish_reason:'length'}]},'chat');
  assert.equal(nativeResponse(c,'responses','m').status,'incomplete');assert.equal(nativeResponse(c,'messages','m').stop_reason,'max_tokens');
});
test('closing a task aborts an active upstream request',async()=>{
  let reached;
  const started=new Promise(resolve=>reached=resolve);
  const upstream=await mock((req,res)=>{reached();});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'chat',model:'m',key:''});
  const request=fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify(input('responses'))}).catch(()=>null);
  await started;bridge.close();
  await request;upstream.close();
});
test('namespace tools retain names and namespace across call/result turns',()=>{
  const tools=[{type:'namespace',name:'files',tools:[{type:'function',...tool}]}];
  const c=canonical({tools,input:[]},'responses');
  const alias=c.tools[0].function.name;
  assert.match(alias,/^ns_[a-f0-9]+$/);
  const result=nativeResponse({...reply,calls:[{...call,function:{...call.function,name:alias}}]},'responses','model',c.custom,c.aliases);
  assert.equal(result.output[1].name,'read_file');assert.equal(result.output[1].namespace,'files');
  const next=canonical({tools,input:[result.output[1],{type:'function_call_output',call_id:'call_1',output:'OK'}]},'responses');
  assert.equal(next.messages[0].tool_calls[0].function.name,alias);
  assert.equal(next.messages[1].tool_call_id,'call_1');
});
test('parallel Responses calls become one assistant turn followed by all tool results',()=>{
  const body={input:[{role:'user',content:'Read two files'},
    {type:'function_call',call_id:'a',name:'read_file',arguments:'{"path":"a"}'},
    {type:'function_call',call_id:'b',name:'read_file',arguments:'{"path":"b"}'},
    {type:'function_call_output',call_id:'a',output:'A'},
    {type:'function_call_output',call_id:'b',output:'B'}]};
  const chat=encode(canonical(body,'responses'),'chat','m');
  assert.deepEqual(chat.messages.map(m=>m.role),['user','assistant','tool','tool']);
  assert.deepEqual(chat.messages[1].tool_calls.map(c=>c.id),['a','b']);
});
test('broken upstream SSE terminates the connection, never appends JSON to an SSE body',async()=>{
  const upstream=await mock((req,res)=>{
    res.writeHead(200,{'content-type':'text/event-stream'});
    res.write('event: response.created\ndata: {}\n\n');
    setTimeout(()=>res.destroy(),30);
  });
  const bridge=await createBridge({endpoint:upstream.url,protocol:'responses',model:'m',key:''});
  try {
    const response=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify(input('responses'))});
    await assert.rejects(response.text());
  } finally {bridge.close();upstream.close();}
});
test('native protocols configure direct CLI connections without conversion-only restrictions',()=>{
  const {agentLaunch}=require('../../runtime-android/src/main/assets/gateway/bridge.cjs');
  for(const mode of ['CLAUDE','CODEX']) {
    const config={endpoint:'https://gateway.example/v1',protocol:mode==='CLAUDE'?'messages':'responses',model:'m',key:'test-secret'};
    const launch=agentLaunch(mode,['exec'],config,{},null);
    assert.ok(!launch.args.join(' ').includes('test-secret'));
    if(mode==='CLAUDE') {assert.equal(launch.env.ANTHROPIC_BASE_URL,'https://gateway.example');assert.equal(launch.env.ANTHROPIC_AUTH_TOKEN,'test-secret');}
    else {assert.ok(launch.args.includes('model_providers.mobby.base_url="https://gateway.example/v1"'));assert.ok(!launch.args.some(a=>a.includes('model_auto_compact_token_limit')));}
  }
});

test('user image bytes and interleaved text survive each cross-protocol conversion', () => {
  const url='data:image/png;base64,iVBORw0KGgo=';
  for (const source of ['messages','responses']) for (const target of ['chat','messages','responses']) {
    const parts=source==='messages' ? [{type:'text',text:'before'},{type:'image',source:{type:'base64',media_type:'image/png',data:'iVBORw0KGgo='}},{type:'text',text:'after'}] :
      [{type:'input_text',text:'before'},{type:'input_image',image_url:url},{type:'input_text',text:'after'}];
    const request=source==='messages'?{messages:[{role:'user',content:parts}]}:{input:[{role:'user',content:parts}]};
    const result=encode(canonical(request,source),target,'model');
    const content=target==='responses'?result.input[0].content:result.messages[0].content;
    assert.equal(content[0].text,'before'); assert.equal(content[2].text,'after');
    if(target==='messages') assert.deepEqual(content[1],{type:'image',source:{type:'base64',media_type:'image/png',data:'iVBORw0KGgo='}});
    else assert.equal(target==='chat'?content[1].image_url.url:content[1].image_url,url);
  }
});

test('image URLs and explicit detail survive where representable and fail rather than disappear elsewhere',()=>{
  const content=[{type:'input_image',image_url:'https://example.com/image.png',detail:'high'}];
  const c=canonical({input:[{role:'user',content}]},'responses');
  assert.equal(encode(c,'chat','m').messages[0].content[0].image_url.detail,'high');
  assert.deepEqual(encode(c,'responses','m').input[0].content,content);
  assert.throws(()=>encode(c,'messages','m'),/detail/);
  for(const block of [{type:'input_image',file_id:'file_1'},{type:'input_image',image_url:'file:///private/image.png'},
    {type:'input_image',image_url:'data:image/png;base64,bad=='},{type:'input_audio',data:'abc'},
    {type:'input_image',image_url:'https://example.com/image.png',transformations:{oversized_image:'error'}}]) {
    assert.throws(()=>canonical({input:[{role:'user',content:[block]}]},'responses'),/跨协议/);
  }
});
test('tool image output survives Messages and Responses but is explicitly rejected for Chat',()=>{
  const image={type:'image',source:{type:'base64',media_type:'image/png',data:'iVBORw0KGgo='}};
  const c=canonical({messages:[{role:'user',content:[{type:'tool_result',tool_use_id:'call_1',content:[{type:'text',text:'result'},image]}]}]},'messages');
  assert.deepEqual(encode(c,'messages','m').messages[0].content[0].content,[{type:'text',text:'result'},image]);
  assert.equal(encode(c,'responses','m').input[0].output[1].image_url,'data:image/png;base64,iVBORw0KGgo=');
  assert.throws(()=>encode(c,'chat','m'),/工具结果中的图片/);
});

test('unrepresentable image detail is a non-retryable request error without an upstream call',async()=>{
  let requests=0;
  const upstream=await mock((req,res)=>{requests++;res.end('{}');});
  const bridge=await createBridge({endpoint:upstream.url,protocol:'messages',model:'m',key:''});
  try {
    const response=await fetch(bridge.url+'/v1/responses',{method:'POST',headers:{authorization:'Bearer '+bridge.token},body:JSON.stringify({input:[{role:'user',content:[{type:'input_image',image_url:'https://example.com/image.png',detail:'high'}]}]})});
    assert.equal(response.status,400);
    assert.match((await response.json()).error.message,/detail/);
    assert.equal(requests,0);
  } finally {bridge.close();upstream.close();}
});
