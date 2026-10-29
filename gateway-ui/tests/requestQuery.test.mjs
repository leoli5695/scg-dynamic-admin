import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readRequestQuery } from '../src/features/request-journey/requestQuery.ts';
import { routeRequestsUrl, routeUsageUrl, routeChangesUrl } from '../src/features/request-journey/links.ts';

const range = { from: '2026-10-29T10:00:00', to: '2026-10-29T11:00:00' };

test('request query retains all combined filters and pagination', () => {
  const query = readRequestQuery(new URLSearchParams({ ...range, source: 'logs', routeId: 'route/a', traceId: 'trace?b', statusCode: '401', page: '2' }));
  assert.deepEqual(query, { ...range, source: 'logs', routeId: 'route/a', traceId: 'trace?b', statusCode: 401, page: 2, error: undefined });
});

test('invalid query cannot silently broaden the search', () => {
  for (const overrides of [{ from: 'invalid' }, { from: '2026-02-30T10:00:00' }, { from: '2026-10-28T24:00:00' }, { to: '2026-10-28T10:00:00' }, { statusCode: '2xx' }, { statusCode: '600' }, { page: '-1' }, { page: '1.5' }, { page: 'Infinity' }]) {
    assert.ok(readRequestQuery(new URLSearchParams({ ...range, ...overrides })).error, JSON.stringify(overrides));
  }
});

test('defaults cover the last hour and use captured traces', () => {
  const query = readRequestQuery(new URLSearchParams(), new Date('2026-10-29T11:00:00'));
  assert.equal(query.from, range.from);
  assert.equal(query.to, range.to);
  assert.equal(query.source, 'traces');
  assert.equal(query.error, undefined);
});

test('route workflow links encode identity and keep usage scoped to the instance', () => {
  const instance = 'instance/a';
  const route = 'route?x&中文';
  for (const [build, page, key] of [[routeRequestsUrl, 'requests', 'routeId'], [routeUsageUrl, 'token_usage', 'routeId'], [routeChangesUrl, 'audit_logs', 'targetId']]) {
    const url = new URL(build(instance, route), 'http://localhost');
    assert.equal(url.pathname, `/instances/instance%2Fa/${page}`);
    assert.equal(url.searchParams.get(key), route);
  }
  assert.equal(new URL(routeChangesUrl(instance, route), 'http://localhost').searchParams.get('targetType'), 'ROUTE');
});
