import { test } from 'node:test';
import assert from 'node:assert/strict';
import { debugDefaults, parseDebugHeaders } from '../src/features/routes/debug/defaults.ts';

test('prefills incoming paths without applying StripPrefix and honors indexed predicate arguments', () => {
  const defaults = debugDefaults({ predicates: [{ name: 'Path', args: { _genkey_0: '/api/users/{id}' } }, { name: 'Method', args: { _genkey_0: 'PUT' } }], filters: [{ name: 'StripPrefix', args: { parts: '1' } }] });
  assert.equal(defaults.path, '/api/users/1');
  assert.equal(defaults.method, 'PUT');
});

test('AI defaults use a bound model mapping and streaming body on the incoming path', () => {
  const defaults = debugDefaults({ category: 'AI', predicates: [{ name: 'Path', args: { pattern: '/ai/**' } }], aiUpstreams: [{ modelMapping: { 'acceptance-normal': 'upstream' } }] });
  assert.equal(defaults.path, '/ai/v1/chat/completions');
  assert.equal(defaults.method, 'POST');
  assert.equal(JSON.parse(defaults.body).model, 'acceptance-normal');
  assert.equal(JSON.parse(defaults.body).stream, true);
});

test('headers must be string-valued objects', () => {
  for (const invalid of ['[]', 'null', '"token"', '{"x":3}']) assert.throws(() => parseDebugHeaders(invalid));
  assert.deepEqual(parseDebugHeaders('{"X-Key":"value"}'), { 'X-Key': 'value' });
});

test('JSON-encoded method and path arrays are decoded before prefilling the request', () => {
  const defaults = debugDefaults({ predicates: [{ name: 'Path', args: { patterns: '["/users/{id}","/other"]' } }, { name: 'Method', args: { methods: '["PATCH","POST"]' } }] });
  assert.equal(defaults.path, '/users/1'); assert.equal(defaults.method, 'PATCH');
});
