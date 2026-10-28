import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeSpanTimeline } from '../src/features/trace/timeline.ts';

test('Unix timestamps become a relative axis and do not mutate original spans', () => {
  const spans = [{ startTimeMs: 1800000000010, durationMs: 40 }, { startTimeMs: 1800000000060, durationMs: 20 }];
  const normalized = normalizeSpanTimeline(spans, 1800000000000);
  assert.deepEqual(normalized.map(span => span.startTimeMs), [10, 60]);
  assert.equal(spans[0].startTimeMs, 1800000000010);
});
test('legacy records use the earliest observed span as origin', () => {
  assert.deepEqual(normalizeSpanTimeline([{ startTimeMs: 1700000000100, durationMs: 100 }, { startTimeMs: 1700000000150, durationMs: 20 }]).map(span => span.startTimeMs), [0, 50]);
});
test('missing times and invalid durations never introduce NaN or negative bar widths', () => {
  assert.deepEqual(normalizeSpanTimeline([{ startTimeMs: undefined, durationMs: NaN }]), [{ startTimeMs: 0, durationMs: 0 }]);
});
