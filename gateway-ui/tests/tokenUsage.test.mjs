import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { isUsageUnresolved, usageTokenValue } from '../src/features/token-usage/usagePresentation.ts';
import { readUsageQuery, updateUsageQuery } from '../src/features/token-usage/usageQuery.ts';

const fields = ['promptTokens', 'completionTokens', 'totalTokens'];

test('unresolved follows the server union, including overlapping failure categories', () => {
  for (const usageStatus of ['UNKNOWN', 'ESTIMATED']) {
    for (const settlementStatus of ['APPLIED', 'NOT_REQUIRED', 'FAILED', 'PENDING', null]) {
      assert.equal(isUsageUnresolved({ usageStatus, settlementStatus }), true);
    }
  }
  for (const usageStatus of ['CONFIRMED', 'RECONCILED', 'LEGACY', 'NOT_BILLED', null]) {
    assert.equal(isUsageUnresolved({ usageStatus, settlementStatus: 'FAILED' }), true);
    assert.equal(isUsageUnresolved({ usageStatus, settlementStatus: 'PENDING' }), true);
    assert.equal(isUsageUnresolved({ usageStatus, settlementStatus: 'APPLIED' }), false);
  }
  assert.equal(isUsageUnresolved({}), false);
});

test('unknown and missing measurements are dashes, never placeholder zeroes', () => {
  for (const field of fields) {
    for (const value of [0, 42, null, undefined]) {
      assert.equal(usageTokenValue({ usageStatus: 'UNKNOWN', [field]: value }, field), '—');
    }
    assert.equal(usageTokenValue({ usageStatus: 'CONFIRMED', [field]: null }, field), '—');
    assert.equal(usageTokenValue({}, field), '—');
  }
});

test('known zeroes, estimates and legacy measurements retain their numeric value', () => {
  for (const usageStatus of ['CONFIRMED', 'ESTIMATED', 'RECONCILED', 'NOT_BILLED', 'LEGACY', null]) {
    for (const field of fields) {
      assert.equal(usageTokenValue({ usageStatus, [field]: 0 }, field), 0);
      assert.equal(usageTokenValue({ usageStatus, [field]: 42 }, field), 42);
      assert.equal(usageTokenValue({ usageStatus, [field]: null }, field), '—');
    }
  }
});

test('instance scope overrides forged URL filters and survives clear, switch and reset', () => {
  const params = new URLSearchParams('instanceId=other&routeId=route-a&unresolvedOnly=true&keep=yes');
  assert.equal(readUsageQuery(params, 'fixed').instanceId, 'fixed');
  for (const instanceId of ['', 'other']) {
    const next = updateUsageQuery(params, { instanceId, routeId: '', unresolvedOnly: false }, 'fixed');
    assert.deepEqual(readUsageQuery(next, 'fixed'), { instanceId: 'fixed', routeId: '', unresolvedOnly: false });
    assert.equal(next.get('instanceId'), 'fixed');
    assert.equal(next.get('keep'), 'yes');
  }
  assert.equal(params.get('instanceId'), 'other', 'updates do not mutate router search params');
});

test('global scope supports all instances and immediate URL back/forward and route changes', () => {
  const original = new URLSearchParams('instanceId=gw-a&routeId=route-a&unresolvedOnly=true');
  const next = updateUsageQuery(original, { instanceId: 'gw/b', routeId: 'route&b', unresolvedOnly: false });
  assert.deepEqual(readUsageQuery(next), { instanceId: 'gw/b', routeId: 'route&b', unresolvedOnly: false });
  assert.deepEqual(readUsageQuery(original), { instanceId: 'gw-a', routeId: 'route-a', unresolvedOnly: true });
  const reset = updateUsageQuery(next, { instanceId: '', routeId: '', unresolvedOnly: false });
  assert.deepEqual(readUsageQuery(reset), { instanceId: '', routeId: '', unresolvedOnly: false });
  assert.equal(readUsageQuery(new URLSearchParams('unresolvedOnly=false')).unresolvedOnly, false);
});

test('all static token usage copy and dynamic statuses exist in both locales', () => {
  const locales = ['zh', 'en'].map((lang) => JSON.parse(readFileSync(new URL(`../src/locales/${lang}/tokenUsage.json`, import.meta.url), 'utf8')));
  assert.deepEqual(Object.keys(locales[0]).sort(), Object.keys(locales[1]).sort());
  const keys = new Set();
  for (const file of ['TokenUsagePage.tsx', 'TokenUsageReconciliationDrawer.tsx']) {
    const source = readFileSync(new URL(`../src/features/token-usage/${file}`, import.meta.url), 'utf8');
    for (const match of source.matchAll(/['"](tokenUsage\.[\w.]+)['"]/g)) keys.add(match[1]);
  }
  for (const status of ['CONFIRMED', 'ESTIMATED', 'UNKNOWN', 'NOT_BILLED', 'LEGACY', 'RECONCILED', 'NOT_REQUIRED', 'PENDING', 'FAILED', 'APPLIED']) keys.add(`tokenUsage.status.${status}`);
  for (const status of ['PENDING', 'APPLIED', 'REVIEW_REQUIRED']) keys.add(`tokenUsage.reconcile.${status}`);
  for (const key of keys) {
    for (const locale of locales) assert.ok(locale[key]?.trim(), `Missing translation: ${key}`);
    const placeholders = (text) => [...text.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort();
    assert.deepEqual(placeholders(locales[0][key]), placeholders(locales[1][key]));
  }
  assert.equal(locales[0]['tokenUsage.card.totalTokens'], '已记录 Token');
  assert.equal(locales[1]['tokenUsage.card.totalTokens'], 'Recorded tokens');
  assert.match(locales[0]['tokenUsage.recordedTokensNote'], /估算.*历史.*并非财务确认/);
  assert.match(locales[1]['tokenUsage.recordedTokensNote'], /estimated.*legacy.*not financially confirmed/);
  assert.match(locales[0]['tokenUsage.pendingWarning'], /重叠.*不可相加/);
  assert.match(locales[1]['tokenUsage.pendingWarning'], /overlap.*do not add/);
});
