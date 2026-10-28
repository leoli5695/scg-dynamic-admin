import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestDiagnosis, requestJourneyUrl, routeConfigurationUrl } from '../src/features/request-journey/links.ts';

test('request context links preserve instance, route and exact trace identity', () => {
  const url = new URL(requestJourneyUrl('gw/a', 'trace?b', 'route&c'), 'http://console');
  assert.equal(url.pathname, '/instances/gw%2Fa/trace/trace%3Fb');
  assert.equal(url.searchParams.get('routeId'), 'route&c');
  assert.equal(new URL(routeConfigurationUrl('gw', 'route&c'), 'http://console').searchParams.get('highlight'), 'route&c');
});
test('diagnosis distinguishes business errors, delayed evidence and confirmed zero usage', () => {
  assert.equal(requestDiagnosis(401, [], true), 'authentication');
  assert.equal(requestDiagnosis(403, [], true), 'authentication');
  assert.equal(requestDiagnosis(429, [], true), 'rateLimit');
  assert.equal(requestDiagnosis(404, [], true), 'routing');
  assert.equal(requestDiagnosis(502, [], true), 'upstream');
  assert.equal(requestDiagnosis(200, [], false), 'ok');
  assert.equal(requestDiagnosis(200, [], true), 'usageMissing');
  assert.equal(requestDiagnosis(undefined, [], true), 'pending');
  assert.equal(requestDiagnosis(undefined, [{usageStatus:'CONFIRMED'}], true), 'traceMissing');
  assert.equal(requestDiagnosis(200, [{usageStatus:'UNKNOWN'}], true), 'usageUnknown');
  assert.equal(requestDiagnosis(200, [{usageStatus:'ESTIMATED'}], true), 'usageUnknown');
  assert.equal(requestDiagnosis(200, [{usageStatus:'CONFIRMED',settlementStatus:'FAILED'}], true), 'settlement');
  assert.equal(requestDiagnosis(200, [{usageStatus:'CONFIRMED',settlementStatus:'NOT_REQUIRED'}], true), 'ok');
});
