import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { getInstanceNavigation } from '../src/features/instance-detail/instanceNavigation.ts';

const legacyPages = [
  'overview', 'services', 'routes', 'plugins', 'plugin_bindings', 'strategies',
  'authentication', 'token_revocation', 'certificates', 'trace', 'monitor',
  'alerts', 'access_log', 'audit_logs', 'diagnostic', 'topology', 'filter_chain',
  'request_replay', 'copilot', 'stress_test', 'events', 'resources', 'middleware',
];

test('seven task-oriented groups plus extensions retain all pages exactly once', () => {
  const groups = getInstanceNavigation(false);
  assert.deepEqual(groups.map(group => group.key), [
    'overview', 'services', 'routes', 'requests', 'usage', 'security', 'operations', 'tools',
  ]);
  const pages = groups.flatMap(group => group.pages);
  assert.equal(new Set(pages).size, pages.length);
  assert.deepEqual([...pages].sort(), [...legacyPages, 'requests', 'token_usage'].sort());
  assert.equal(groups.find(group => group.key === 'requests').pages[0], 'requests');
  assert.ok(groups.find(group => group.key === 'tools').pages.includes('plugins'));
});

test('external instances hide only Kubernetes pages without mutating cluster navigation', () => {
  const cluster = getInstanceNavigation(false).flatMap(group => group.pages);
  const external = getInstanceNavigation(true).flatMap(group => group.pages);
  assert.deepEqual(external, cluster.filter(page => !['events', 'resources'].includes(page)));
  assert.ok(external.includes('certificates') && external.includes('token_revocation'));
  assert.equal(getInstanceNavigation(false).flatMap(group => group.pages).length, 25);
});

test('all legacy URLs and both workflow URLs remain registered', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8');
  for (const page of [...legacyPages.filter(page => page !== 'overview'), 'requests', 'token_usage']) {
    assert.ok(app.includes(`path="${page}"`), `missing ${page}`);
  }
  for (const path of ['trace/:traceId', 'routes/:routeId/debug', '/token-usage']) {
    assert.ok(app.includes(`path="${path}"`));
  }
});
