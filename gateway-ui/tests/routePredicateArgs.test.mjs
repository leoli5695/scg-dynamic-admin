import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildRoutePayload, extractMethods, stringifyPredicateArgs,
} from '../src/features/routes/utils.ts';

// Mirrors RouteFormModal's edit-mode initial values: the input shows the
// stringified value while the stored args ride along for a lossless save.
const editRow = (name, args) => ({
  name,
  args: name === 'Method' ? extractMethods(args) : stringifyPredicateArgs(name, args),
  originalArgs: args,
});

const buildPayload = (rows) => buildRoutePayload(
  { routeName: 'r', serviceId: 'static://svc', predicates: rows },
  'SINGLE',
  false,
  { services: [] },
);

// buildRoutePayload requires a Path predicate; this appends a non-Path row to one.
const payloadWithPath = (row) => buildPayload([{ name: 'Path', args: '/required/**' }, row]).predicates[1];

test('shortcut-form Path args display the real pattern instead of the /** placeholder', () => {
  assert.equal(
    stringifyPredicateArgs('Path', { _genkey_0: '/_acceptance/ai/**' }),
    '/_acceptance/ai/**',
  );
  assert.equal(
    stringifyPredicateArgs('Path', { _genkey_0: '/a/**', _genkey_1: '/b/**' }),
    '/a/**, /b/**',
  );
});

test('an untouched edit republishes shortcut-form args byte-for-byte', () => {
  const original = { _genkey_0: '/_acceptance/ai/**' };
  const payload = buildPayload([editRow('Path', original)]);
  // The pre-fix builder displayed "/**" and published { pattern: "/**" }.
  assert.equal(JSON.stringify(payload.predicates), JSON.stringify([{ name: 'Path', args: original }]));
});

test('editing a shortcut-form path updates the original key and keeps its siblings', () => {
  const original = { _genkey_0: '/a/**', _genkey_1: '/b/**' };
  const row = editRow('Path', original);
  row.args = '/a/v2/**, /b/**';
  assert.deepEqual(buildPayload([row]).predicates[0].args, {
    _genkey_0: '/a/v2/**',
    _genkey_1: '/b/**',
  });
});

test('named-form Path args keep the current pattern-key behavior', () => {
  const original = { pattern: '/api/**' };
  const row = editRow('Path', original);
  assert.equal(row.args, '/api/**');
  assert.deepEqual(buildPayload([row]).predicates[0].args, { pattern: '/api/**' });

  row.args = '/api/v2/**';
  assert.deepEqual(buildPayload([row]).predicates[0].args, { pattern: '/api/v2/**' });
});

test('shortcut-form Host and Header args display and republish losslessly', () => {
  const host = { _genkey_0: '*.example.com', _genkey_1: 'api.example.com' };
  assert.equal(stringifyPredicateArgs('Host', host), '*.example.com, api.example.com');
  const header = { _genkey_0: 'X-Tenant', _genkey_1: 'team-.*' };
  assert.equal(stringifyPredicateArgs('Header', header), 'X-Tenant:team-.*');

  assert.deepEqual(payloadWithPath(editRow('Host', host)).args, host);
  assert.deepEqual(payloadWithPath(editRow('Header', header)).args, header);

  const editedHost = editRow('Host', host);
  editedHost.args = '*.example.org, api.example.com';
  assert.deepEqual(payloadWithPath(editedHost).args, {
    _genkey_0: '*.example.org',
    _genkey_1: 'api.example.com',
  });

  const editedHeader = editRow('Header', header);
  editedHeader.args = 'X-Tenant:blue-.*';
  assert.deepEqual(payloadWithPath(editedHeader).args, {
    _genkey_0: 'X-Tenant',
    _genkey_1: 'blue-.*',
  });
});

test('shortcut-form Method args round-trip through the multi-select', () => {
  const original = { _genkey_0: 'GET', _genkey_1: 'POST' };
  const row = editRow('Method', original);
  assert.deepEqual(row.args, ['GET', 'POST']);
  assert.deepEqual(payloadWithPath(row).args, original);

  row.args = ['PUT'];
  assert.deepEqual(payloadWithPath(row).args, { _genkey_0: 'PUT' });
});

test('a new predicate still publishes the named form', () => {
  const payload = buildPayload([{ name: 'Path', args: '/new/**' }]);
  assert.deepEqual(payload.predicates[0].args, { pattern: '/new/**' });
});