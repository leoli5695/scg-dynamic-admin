import { test } from 'node:test';
import assert from 'node:assert/strict';
import { responseView } from '../src/features/routes/debug/responseView.ts';
const frame = data => `data: ${JSON.stringify(data)}\n\n`;

test('streamed answer is concatenated and usage is read once without adding reasoning details again', () => {
  const raw = frame({choices:[{delta:{reasoning_content:'provider reasoning',content:''}}],usage:null})
    + ['Hello! How',' can I help you',' today?'].map(content => frame({choices:[{index:0,delta:{content}}],usage:null})).join('')
    + frame({choices:[],usage:{prompt_tokens:62,completion_tokens:36,total_tokens:98,completion_tokens_details:{reasoning_tokens:24}}})
    + 'data: [DONE]\n\n';
  const result = responseView(raw,true);
  assert.equal(result.answer,'Hello! How can I help you today?');
  assert.equal(result.reasoning,'provider reasoning');
  assert.deepEqual(result.usage,{input:62,output:36,total:98});
  assert.equal(result.complete,true);
  assert.equal(result.formatted,raw);
});
test('partial live frames do not overwrite a visible answer or produce JSON fragments', () => {
  const partial = frame({choices:[{delta:{content:'Hello'}}]})+'data: {"choices":[{"delta":{"content":" wor';
  assert.equal(responseView(partial,true).answer,'Hello');
  assert.equal(responseView(partial+'ld"}}]}\n\n',true).answer,'Hello world');
});
test('normal JSON, plain text and provider errors stay visible; zero usage is retained', () => {
  assert.equal(responseView('{"hello":true}',false).formatted,'{\n  "hello": true\n}');
  assert.equal(responseView('plain response',false).formatted,'plain response');
  const json={choices:[{message:{content:'answer'}}],usage:{prompt_tokens:0,completion_tokens:0,total_tokens:0}};
  assert.equal(responseView(JSON.stringify(json),false).answer,'answer');
  assert.equal(responseView(JSON.stringify(json),false).usage.total,0);
  assert.equal(responseView('{"error":{"message":"rate limited"}}',false).modelResponse,false);
});
test('tool-call fragments are merged and do not turn into the text answer', () => {
  const raw=frame({choices:[{delta:{tool_calls:[{index:0,function:{name:'lookup',arguments:'{"id":'}}]}}]})
    +frame({choices:[{delta:{tool_calls:[{index:0,function:{arguments:'1}'}}]}}]});
  assert.deepEqual(responseView(raw,true).tools,[{name:'lookup',arguments:'{"id":1}'}]);
  assert.equal(responseView(raw,true).answer,'');
});
test('Anthropic and Responses events use their native termination and cumulative usage', () => {
  const native=frame({type:'message_start',message:{usage:{input_tokens:10,output_tokens:1,cache_read_input_tokens:20}}})
    +frame({type:'content_block_delta',delta:{text:'hello'}})+frame({type:'message_delta',usage:{output_tokens:5}})
    +frame({type:'message_stop'});
  assert.equal(responseView(native,true).answer,'hello');
  assert.deepEqual(responseView(native,true).usage,{input:30,output:5,total:35});
  assert.equal(responseView(native,true).complete,true);
  const responses=frame({type:'response.output_text.delta',delta:'hello'})
    +frame({type:'response.completed',response:{usage:{input_tokens:3,output_tokens:2,total_tokens:5}}});
  assert.equal(responseView(responses,true).answer,'hello');
  assert.equal(responseView(responses,true).usage.total,5);
  assert.equal(responseView(responses,true).complete,true);
});
