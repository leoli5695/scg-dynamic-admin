import {test} from 'node:test';
import assert from 'node:assert/strict';
import {rawFrames,jsonTokens} from '../src/features/routes/debug/rawFrames.ts';

test('SSE frames retain metadata and identify usage and terminal events without discarding nulls',()=>{
  const raw=': heartbeat\r\n\r\nevent: message\r\nid: event-a\r\ndata: {"choices":[],"usage":null}\r\n\r\n'
    +'data: {"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}\n\ndata: [DONE]\n\n';
  const frames=rawFrames(raw);
  assert.equal(frames.length,3);
  assert.equal(frames[0].id,'event-a');
  assert.equal(frames[0].event,'message');
  assert.equal(frames[0].usage,false);
  assert.equal(frames[0].value.usage,null);
  assert.equal(frames[1].usage,true);
  assert.equal(frames[1].value.usage.total_tokens,0);
  assert.equal(frames[2].kind,'done');
});
test('multiline JSON, partial events and non-JSON data remain inspectable',()=>{
  const answer=rawFrames('data: {"choices":[{"delta":{"content":"Hello"}}]}\n\n')[0];
  assert.equal(answer.contentType,'answer');
  assert.equal(answer.preview,'Hello');
  assert.deepEqual(rawFrames('data: {"a":\ndata: true}\n\n')[0].value,{a:true});
  assert.equal(rawFrames('data: {"a":')[0].kind,'partial');
  assert.equal(rawFrames('data: plain text\n\n')[0].data,'plain text');
  assert.equal(rawFrames('data: {"type":"response.completed","response":{"usage":{"total_tokens":5}}}\n\n')[0].usage,true);
});
test('JSON token rendering preserves exact data as text, including markup and escaped quotes',()=>{
  const value={message:'<script>alert("x")</script>',count:98,enabled:true,usage:null};
  const tokens=jsonTokens(value);
  assert.equal(tokens.map(token=>token.text).join(''),JSON.stringify(value,null,2));
  assert.ok(tokens.some(token=>token.kind==='key'));
  assert.ok(tokens.some(token=>token.kind==='string'&&token.text.includes('<script>')));
  assert.ok(tokens.some(token=>token.kind==='number'&&token.text==='98'));
});
