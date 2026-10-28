/** Normalize Unix timestamps to a shared relative axis without mutating persisted spans. */
export function normalizeSpanTimeline<T extends { startTimeMs?: number; durationMs: number }>(spans: T[], origin?: number): T[] {
  const starts = spans.map(span => span.startTimeMs).filter((time): time is number => Number.isFinite(time) && (time ?? 0) > 0);
  const base = Number.isFinite(origin) && (origin ?? 0) > 0 ? origin! : starts.length ? Math.min(...starts) : 0;
  return spans.map(span => ({ ...span,
    startTimeMs: Number.isFinite(span.startTimeMs) ? Math.max(0, span.startTimeMs! - base) : 0,
    durationMs: Number.isFinite(span.durationMs) ? Math.max(0, span.durationMs) : 0,
  }));
}
