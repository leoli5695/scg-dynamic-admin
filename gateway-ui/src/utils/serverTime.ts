/**
 * Backend serializes LocalDateTime as a naive string. The JDBC URL binds wall clocks as
 * Asia/Shanghai (serverTimezone) while the JVM runs UTC, so the naive string that reaches
 * the frontend represents Shanghai wall time. A bare `new Date()` lets the browser assume
 * its own zone and shifts every displayed server time by the offset (8h for CN users).
 * Epoch numbers and strings that already carry an offset/Z pass through untouched.
 */
const OFFSET_SUFFIX_RE = /(Z|[+-]\d{2}:?\d{2})$/i;

export const parseServerTime = (value: string | number): Date => {
  if (typeof value === 'number') return new Date(value);
  const trimmed = value.trim();
  if (OFFSET_SUFFIX_RE.test(trimmed)) return new Date(trimmed);
  return new Date(`${trimmed.replace(' ', 'T')}+08:00`);
};

export default parseServerTime;
