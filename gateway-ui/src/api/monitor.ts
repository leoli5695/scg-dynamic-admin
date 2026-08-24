/**
 * Monitor API — endpoints aligned with controller/MonitorController.java
 * (/api/monitor). Standard ApiResponse envelope, auto-unwrapped by httpClient.
 */
import httpClient, { RequestOptions } from './http';
import type {
  MonitorHistoryPayload,
  MonitorMetricsPayload,
  MonitorRoutesPayload,
  MonitorStatusPayload,
} from '../types/monitor';

const BASE = '/api/monitor';

/** GET /api/monitor/metrics — current snapshot metrics. */
export const getMonitorMetrics = (
  params: { instanceId?: string; podInstance?: string; timestamp?: number },
  options?: RequestOptions,
) => httpClient.get<MonitorMetricsPayload>(`${BASE}/metrics`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/monitor/status — Prometheus availability. */
export const getMonitorStatus = (options?: RequestOptions) =>
  httpClient.get<MonitorStatusPayload>(`${BASE}/status`, options);

/** GET /api/monitor/history — range series for trend charts. */
export const getMonitorHistory = (
  params: { hours: number; instanceId?: string; podInstance?: string; centerTime?: number },
  options?: RequestOptions,
) => httpClient.get<MonitorHistoryPayload>(`${BASE}/history`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/monitor/routes — per-route request/error/latency stats. */
export const getMonitorRouteMetrics = (
  params: { hours: number; instanceId?: string; podInstance?: string },
  options?: RequestOptions,
) => httpClient.get<MonitorRoutesPayload>(`${BASE}/routes`, {
  ...options,
  params: params as Record<string, unknown>,
});
