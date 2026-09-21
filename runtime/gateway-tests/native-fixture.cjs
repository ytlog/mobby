'use strict';
// Synthetic native model responses for integration tests only.
const {randomUUID}=require('node:crypto');
const id=prefix=>prefix+randomUUID().replaceAll('-','');
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
module.exports={nativeResponse,sendNative};
