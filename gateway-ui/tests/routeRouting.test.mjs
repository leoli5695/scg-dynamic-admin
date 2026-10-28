import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildRoutePayload, toBackendBinding, toFormBinding } from '../src/features/routes/utils.ts';

test('zero-weight canary remains zero when the form is submitted', () => {
  const payload = buildRoutePayload({
    routeName: 'gray', predicates: [{ name: 'Path', args: { pattern: '/api/**' } }],
    services: [{ serviceId: 'static://stable', weight: 100 }, { serviceId: 'lb://canary', weight: 0 }],
    grayRules: [{ type: 'HEADER', name: 'X-Canary', value: 'yes', targetVersion: 'canary' }],
  }, 'MULTI', true, { services: [] });
  assert.deepEqual(payload.services.map(s => s.weight), [100, 0]);
  assert.deepEqual(payload.services.map(s => s.type), ['STATIC', 'DISCOVERY']);
  assert.equal(payload.grayRules.rules[0].targetVersion, 'canary');
});

test('editing a stored protocol does not depend on current registry availability', () => {
  for (const type of ['STATIC', 'DISCOVERY', 'NACOS', 'CONSUL']) {
    const binding = { type, serviceId: 'orders', serviceNamespace: 'staging', serviceGroup: 'BLUE', weight: 0, enabled: true };
    const form = toFormBinding(binding, [], []);
    assert.equal(form.serviceId.startsWith(type === 'STATIC' ? 'static://' : 'lb://'), true);
    const saved = toBackendBinding(form, []);
    assert.equal(saved.type, type === 'STATIC' ? 'STATIC' : 'DISCOVERY');
    assert.equal(saved.weight, 0);
    if (type !== 'STATIC') {
      assert.equal(saved.serviceNamespace, 'staging');
      assert.equal(saved.serviceGroup, 'BLUE');
    }
  }
  assert.equal(toFormBinding({ type: 'STATIC', serviceId: 'orders' }, [], [{ serviceName: 'orders' }]).serviceId, 'static://orders');
});

test('single discovery publish separates namespace/group qualifiers from the URI', () => {
  const payload = buildRoutePayload({
    routeName: 'orders', serviceId: 'lb://orders?ns=staging&grp=BLUE',
    predicates: [{ name: 'Path', args: { pattern: '/api/**' } }],
  }, 'SINGLE', false, { services: [] });
  assert.equal(payload.uri, 'lb://orders');
  assert.equal(payload.serviceNamespace, 'staging');
  assert.equal(payload.serviceGroup, 'BLUE');
});
