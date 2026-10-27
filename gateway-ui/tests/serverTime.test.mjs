import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseServerTime, formatServerTime } from '../src/utils/serverTime.ts';

test('nullable backend timestamps never crash rendering and display a placeholder', () => {
  for (const value of [null, undefined, '', '   ', 'not-a-date']) {
    assert.ok(Number.isNaN(parseServerTime(value).getTime()));
    assert.equal(formatServerTime(value), '—');
  }
});

test('naive backend timestamps preserve Shanghai wall time', () => {
  for (const value of ['2026-10-28 12:30:00', '2026-10-28T12:30:00', ' 2026-10-28T12:30:00 ']) {
    assert.equal(parseServerTime(value).toISOString(), '2026-10-28T04:30:00.000Z');
  }
});

test('explicit offsets and epoch zero retain their instant', () => {
  assert.equal(parseServerTime('2026-10-28T04:30:00Z').toISOString(), '2026-10-28T04:30:00.000Z');
  assert.equal(
    parseServerTime('2026-10-28T12:30:00+08:00').toISOString(),
    '2026-10-28T04:30:00.000Z',
  );
  assert.equal(parseServerTime(0).getTime(), 0);
  assert.notEqual(formatServerTime(0), '—');
});
