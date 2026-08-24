/**
 * Service middleware API.
 * Endpoints aligned with controller/ServiceMiddlewareViewController.java
 * (/api/service-middleware).
 *
 * Backend notes:
 * - Every endpoint returns a manual envelope {code, data, message, ...} and
 *   keeps HTTP 200 even on internal failure (code=500) — callers must check
 *   the code field.
 * - GET /services takes NO query params (the legacy page sent instanceId but
 *   the backend ignored it); middleware data is global, not instance-scoped.
 */
import httpClient, { RequestOptions } from './http';
import type {
  MiddlewareDetailResponse,
  MiddlewareMetricsResponse,
  ServiceMiddlewareListResponse,
} from '../types/middleware';

const BASE = '/api/service-middleware';

/** GET /api/service-middleware/services — all services grouped with middlewares. */
export const listServiceMiddlewares = (options?: RequestOptions) =>
  httpClient.get<ServiceMiddlewareListResponse>(`${BASE}/services`, options);

/** GET /api/service-middleware/{serviceName}/metrics — first instance's metrics. */
export const getServiceMiddlewareMetrics = (
  serviceName: string,
  options?: RequestOptions,
) => httpClient.get<MiddlewareMetricsResponse>(
  `${BASE}/${encodeURIComponent(serviceName)}/metrics`,
  options,
);

/** GET /{serviceName}/middleware/{middlewareType}/detail — instant + trend metrics. */
export const getMiddlewareDetail = (
  serviceName: string,
  middlewareType: string,
  params: { exporterUrl?: string; start?: number; end?: number },
  options?: RequestOptions,
) => httpClient.get<MiddlewareDetailResponse>(
  `${BASE}/${encodeURIComponent(serviceName)}/middleware/${encodeURIComponent(middlewareType)}/detail`,
  {
    ...options,
    params: params as Record<string, unknown>,
  },
);
