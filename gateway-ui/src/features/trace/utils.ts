import dayjs from 'dayjs';
import type { FilterChainExecution, FilterExecutionBar, RequestTrace } from '../../types/trace';

/** Column visibility presets for the trace table. */
export const DEFAULT_VISIBLE_COLUMNS = ['traceId', 'method', 'path', 'statusCode', 'latencyMs', 'traceTime', 'actions'];
export const ALL_COLUMNS = ['traceId', 'method', 'path', 'statusCode', 'latencyMs', 'clientIp', 'traceTime', 'replayCount', 'actions'];

/** Client-side time range presets (minutes). */
export const TIME_RANGE_OPTIONS = [
  { label: '15m', value: 15 },
  { label: '1h', value: 60 },
  { label: '6h', value: 360 },
  { label: '24h', value: 1440 },
  { label: '7d', value: 10080 },
];

export type MethodTagColor = 'green' | 'blue' | 'orange' | 'red' | 'cyan' | 'default';

export const getMethodColor = (method: string): MethodTagColor => {
  switch (method.toUpperCase()) {
    case 'GET': return 'green';
    case 'POST': return 'blue';
    case 'PUT': return 'orange';
    case 'DELETE': return 'red';
    case 'PATCH': return 'cyan';
    default: return 'default';
  }
};

export type StatusBadgeColor = 'success' | 'processing' | 'warning' | 'error';

export const getStatusColor = (statusCode: number): StatusBadgeColor => {
  if (statusCode >= 500) return 'error';
  if (statusCode >= 400) return 'warning';
  if (statusCode >= 300) return 'processing';
  return 'success';
};

/** Pretty-print a JSON string, returning the input untouched when invalid. */
export const formatJson = (str?: string | null): string => {
  if (!str) return '-';
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
};

/** Count keys of a JSON header string (0 when invalid). */
export const getHeadersCount = (headers?: string | null): number => {
  if (!headers) return 0;
  try {
    return Object.keys(JSON.parse(headers)).length;
  } catch {
    return 0;
  }
};

/** True when a body exceeds 500 chars (toggle expansion). */
export const isBodyLarge = (body?: string | null): boolean => (body?.length || 0) > 500;

/**
 * Backend returns timePercentage pre-formatted ("34.5%"); parse it to a
 * number so progress bars render correctly.
 */
export const parsePercentage = (value?: string | number): number => {
  if (typeof value === 'number') return value;
  if (!value) return 0;
  const parsed = parseFloat(String(value));
  return Number.isFinite(parsed) ? parsed : 0;
};

/**
 * Build waterfall bars from filter executions.
 * Uses the real selfTimeMs from the backend (FilterChainExecutionService),
 * falling back to a 30% estimate only when it is missing.
 */
export const toFilterExecutionBars = (executions: FilterChainExecution[]): FilterExecutionBar[] => {
  if (!executions || executions.length === 0) return [];

  let cumulativeOffset = 0;
  return executions.map((exec) => {
    const totalTimeMs = exec.durationMs ?? 0;
    const selfTimeMs = exec.selfTimeMs && exec.selfTimeMs > 0
      ? Math.min(exec.selfTimeMs, totalTimeMs)
      : totalTimeMs * 0.3;
    const bar: FilterExecutionBar = {
      filterName: exec.filterName,
      order: exec.filterOrder,
      startTimeOffset: cumulativeOffset,
      selfTimeMs,
      totalTimeMs,
      downstreamMs: totalTimeMs - selfTimeMs,
      success: exec.success,
      errorMessage: exec.errorMessage,
      timePercentage: parsePercentage(exec.timePercentage),
    };
    cumulativeOffset += selfTimeMs;
    return bar;
  });
};

export const getFilterPercentageColor = (percentage: number): string => {
  if (percentage >= 50) return '#cf1322';
  if (percentage >= 30) return '#faad14';
  return '#52c41a';
};

/** Download the current (filtered) trace list as JSON or CSV. */
export const exportTraces = (data: RequestTrace[], format: 'json' | 'csv'): void => {
  const stamp = dayjs().format('YYYY-MM-DD-HHmmss');
  let content: string;
  let mime: string;
  if (format === 'json') {
    content = JSON.stringify(data, null, 2);
    mime = 'application/json';
  } else {
    const headers = ['Trace ID', 'Method', 'Path', 'Status', 'Latency(ms)', 'Client IP', 'Time'];
    const rows = data.map((t) => [
      t.traceId,
      t.method,
      t.path ?? '',
      t.statusCode,
      t.latencyMs,
      t.clientIp ?? '',
      t.traceTime,
    ]);
    content = [headers, ...rows].map((r) => r.join(',')).join('\n');
    mime = 'text/csv';
  }
  const url = URL.createObjectURL(new Blob([content], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `traces-${stamp}.${format}`;
  a.click();
  URL.revokeObjectURL(url);
};
