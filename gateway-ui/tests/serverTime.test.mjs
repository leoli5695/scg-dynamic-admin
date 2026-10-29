import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseServerTime, formatServerTime } from '../src/utils/serverTime.ts';
import { spawnSync } from 'node:child_process';

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

test('heartbeat offsets preserve a five-second age in every browser timezone', () => {
  const module = new URL('../src/utils/serverTime.ts', import.meta.url).href;
  const code = `import {parseServerTime} from ${JSON.stringify(module)};
    const now = Date.parse('2026-10-29T13:00:05Z');
    for (const value of ['2026-10-29T13:00:00Z','2026-10-29T21:00:00+08:00','2026-10-29T09:00:00-04:00']) {
      if (now - parseServerTime(value).getTime() !== 5000) process.exit(1);
    }`;
  for (const zone of ['UTC', 'Asia/Shanghai', 'America/New_York']) {
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', code], { env: { ...process.env, TZ: zone }, encoding: 'utf8' });
    assert.equal(result.status, 0, `${zone}: ${result.stderr}`);
  }
});
