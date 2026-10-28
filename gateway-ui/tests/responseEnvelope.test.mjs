import { test } from 'node:test';
import assert from 'node:assert/strict';
import { unwrapApiResponse } from '../src/api/responseEnvelope.ts';

const read = body => unwrapApiResponse(body, (message, code) => { throw Object.assign(new Error(message), { code }); });

test('no reconciliation returns null when NON_NULL serialization omits data', () => {
  assert.equal(read({ code: 200, message: 'Success', timestamp: 1793200000000 }), null);
  assert.equal(read({ code: 200, message: 'Success', data: null }), null);
});

test('an existing reconciliation preserves its original snapshot and zero adjustments', () => {
  const command = { id: 'correction-1', status: 'APPLIED', original_usage: '{"total_tokens":5}', monthly_delta: 0 };
  assert.equal(read({ code: 200, message: 'Success', data: command }), command);
  assert.equal(read({ code: 200, data: false }), false);
  assert.equal(read({ code: 200, data: 0 }), 0);
});

test('business errors without data still fail instead of becoming display records', () => {
  assert.throws(() => read({ code: 403, message: 'Forbidden' }), { message: 'Forbidden', code: 403 });
});

test('legacy raw payloads remain intact, including a non-envelope code field', () => {
  for (const raw of [[{ id: 1 }], { id: 1 }, { code: 200, name: 'raw-status' }]) assert.equal(read(raw), raw);
});
