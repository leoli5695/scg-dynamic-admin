import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getInstanceNavigation } from '../src/features/instance-detail/instanceNavigation.ts';

test('all existing instance routes are reachable exactly once across six groups', () => {
  const groups = getInstanceNavigation(false);
  const pages = groups.flatMap((group) => group.pages);
  assert.equal(groups.length, 6);
  assert.equal(pages.length, 23);
  assert.equal(new Set(pages).size, 23);
  assert.deepEqual([...pages].sort(), [
    'overview', 'services', 'routes', 'plugins', 'plugin_bindings', 'strategies',
    'authentication', 'token_revocation', 'certificates', 'trace', 'monitor',
    'alerts', 'access_log', 'audit_logs', 'diagnostic', 'topology', 'filter_chain',
    'request_replay', 'copilot', 'stress_test', 'events', 'resources', 'middleware',
  ].sort());
});

test('external instances hide only Kubernetes pages without mutating cluster navigation', () => {
  const pages = getInstanceNavigation(true).flatMap((group) => group.pages);
  assert.equal(pages.length, 21);
  assert.ok(!pages.includes('events') && !pages.includes('resources'));
  assert.ok(pages.includes('middleware') && pages.includes('stress_test'));
  assert.equal(getInstanceNavigation(false).flatMap((group) => group.pages).length, 23);
});
