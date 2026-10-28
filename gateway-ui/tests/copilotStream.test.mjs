import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readCopilotStream } from '../src/api/copilotStream.ts';
import { copilotSessionStorageKey, createCopilotSessionId } from '../src/features/copilot/utils/session.ts';

const encoder = new TextEncoder();

test('session storage is isolated by account and instance without delimiter collisions', () => {
  assert.notEqual(copilotSessionStorageKey('alice', 'a'), copilotSessionStorageKey('bob', 'a'));
  assert.notEqual(copilotSessionStorageKey('alice', 'a'), copilotSessionStorageKey('alice', 'b'));
  assert.notEqual(copilotSessionStorageKey('a:b', 'c'), copilotSessionStorageKey('a', 'b:c'));
  const first = createCopilotSessionId();
  assert.match(first, /^[a-f0-9]{32}$/);
  assert.notEqual(first, createCopilotSessionId());
});
function stream(chunks, onCancel = () => {}) {
  return new ReadableStream({
    start(controller) { for (const chunk of chunks) controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk); controller.close(); },
    cancel: onCancel,
  });
}

test('CRLF and UTF-8 may be split at every byte boundary', async () => {
  const bytes = encoder.encode('event: delta\r\ndata: {"text":"你好"}\r\n\r\nevent: message\r\ndata: {"success":true}\r\n\r\n');
  const events = [];
  const body = stream(Array.from(bytes, byte => Uint8Array.of(byte)));
  await readCopilotStream(body, (event, data) => { events.push([event, JSON.parse(data)]); return event === 'message'; });
  assert.deepEqual(events, [['delta', { text: '你好' }], ['message', { success: true }]]);
  assert.equal(body.locked, false);
});

test('partial text without a final event rejects instead of silently losing the answer', async () => {
  const body = stream(['event: delta\ndata: {"text":"partial"}\n\n']);
  await assert.rejects(readCopilotStream(body, () => false), /before the final reply/);
  assert.equal(body.locked, false);
});

test('an error event is terminal and late events are ignored', async () => {
  const events = [];
  await readCopilotStream(stream(['event: error\ndata: {"message":"failed"}\n\nevent: message\ndata: {}\n\n']),
    (event, data) => { events.push([event, data]); return event === 'error'; });
  assert.equal(events.length, 1);
});

test('a final JSON event at EOF works without a trailing blank line', async () => {
  let received;
  await readCopilotStream(stream(['event: message\ndata: {"success":true}']),
    (event, data) => { received = JSON.parse(data); return event === 'message'; });
  assert.deepEqual(received, { success: true });
});

test('SSE comments and multiline data do not create phantom messages', async () => {
  const events = [];
  await readCopilotStream(stream([': heartbeat\n\nevent: message\ndata: {"success":\ndata: true}\n\n']),
    (event, data) => { events.push(JSON.parse(data)); return event === 'message'; });
  assert.deepEqual(events, [{ success: true }]);
});

test('oversized unfinished frames are rejected with a bounded buffer', async () => {
  await assert.rejects(readCopilotStream(stream(['data: ' + 'a'.repeat(2 * 1024 * 1024 + 1)]), () => false), /size limit/);
});

test('aborting a stalled reader cancels the stream and releases its lock', async () => {
  let cancelled = false;
  const body = new ReadableStream({ cancel() { cancelled = true; } });
  const controller = new AbortController();
  const reading = readCopilotStream(body, () => false, controller.signal);
  controller.abort();
  await assert.rejects(reading, { name: 'AbortError' });
  assert.equal(cancelled, true);
  assert.equal(body.locked, false);
});

test('callback failures release the reader instead of leaving a live stream behind', async () => {
  const body = stream(['event: message\ndata: broken\n\n']);
  await assert.rejects(readCopilotStream(body, () => { throw new Error('invalid payload'); }), /invalid payload/);
  assert.equal(body.locked, false);
});
