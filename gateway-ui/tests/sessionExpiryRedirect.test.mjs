import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

function clientHarness(pathname = '/token-usage') {
  const values = new Map(['token', 'username', 'nickname', 'role', 'licenseEdition', 'theme'].map(key => [key, key]));
  const redirects = [];
  const toasts = [];
  let onError;
  const location = { pathname, set href(value) { redirects.push(value); this.pathname = value; } };
  const axios = {
    create: () => ({ interceptors: { request: { use() {} }, response: { use: (_, handler) => { onError = handler; } } } }),
    isCancel: () => false, isAxiosError: () => false,
  };
  const mocks = {
    axios: { __esModule: true, default: axios },
    '../i18n': { __esModule: true, default: { t: key => key } },
    '../utils/logger': { __esModule: true, default: { error() {} } },
    '../utils/antdStatic': { message: { error: value => toasts.push(value) } },
    './responseEnvelope': {},
  };
  const source = readFileSync(new URL('../src/api/http.ts', import.meta.url), 'utf8').replaceAll('import.meta.env', '({})');
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText;
  new Function('require', 'exports', 'localStorage', 'window', compiled)(id => {
    assert.ok(id in mocks, id); return mocks[id];
  }, {}, { getItem: key => values.get(key), removeItem: key => values.delete(key) }, { location });
  return { values, redirects, toasts, fail: status => onError({ response: { status } }) };
}

test('expired login 401 clears the complete session and redirects to login', async () => {
  const h = clientHarness();
  await assert.rejects(h.fail(401));
  for (const key of ['token', 'username', 'nickname', 'role', 'licenseEdition']) assert.equal(h.values.has(key), false);
  assert.equal(h.values.get('theme'), 'theme');
  assert.deepEqual(h.redirects, ['/login']);
  assert.deepEqual(h.toasts, []);
  await assert.rejects(h.fail(401));
  assert.deepEqual(h.redirects, ['/login'], 'parallel failures must not loop on the login page');
});

test('genuine permission denial 403 preserves login and shows the permission message', async () => {
  const h = clientHarness();
  await assert.rejects(h.fail(403));
  assert.equal(h.values.get('token'), 'token');
  assert.deepEqual(h.redirects, []);
  assert.deepEqual(h.toasts, ['common.error_no_permission']);
});

test('401 on the login page does not redirect the failed login back to itself', async () => {
  const h = clientHarness('/login');
  await assert.rejects(h.fail(401));
  assert.deepEqual(h.redirects, []);
});
