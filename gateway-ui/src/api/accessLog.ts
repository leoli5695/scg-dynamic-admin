/**
 * Access log API.
 * Endpoints aligned with controller/AccessLogConfigController.java
 * (/api/access-log). Every endpoint returns the standard ApiResponse
 * envelope, so httpClient unwraps `data` automatically.
 *
 * Note: GET /entries and /stats read logs for a single `date` (YYYY-MM-DD,
 * defaults to today server-side). The old page sent startTime/endTime there,
 * which the backend silently ignored.
 */
import httpClient, { RequestOptions } from './http';
import type {
  AccessLogConfig, AccessLogEntriesPage, AccessLogQueryFilters,
  AccessLogStats, DeployModeOption, GatewayPodInfo,
} from '../types/accessLog';

const BASE = '/api/access-log';

/** GET /api/access-log/config */
export const getAccessLogConfig = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<AccessLogConfig>(`${BASE}/config`, { ...options, params });

/** POST /api/access-log/config — body must include instanceId. */
export const saveAccessLogConfig = (
  body: Partial<AccessLogConfig> & { instanceId: string },
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/config`, body, options);

/** GET /api/access-log/deploy-modes */
export const getDeployModes = (options?: RequestOptions) =>
  httpClient.get<DeployModeOption[]>(`${BASE}/deploy-modes`, options);

/** POST /api/access-log/reset?instanceId=... */
export const resetAccessLogConfig = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/reset`, undefined, { ...options, params });

/* ------------------------------ Log viewing ------------------------------ */

export interface LocalLogQuery extends AccessLogQueryFilters {
  /** YYYY-MM-DD; backend defaults to today. */
  date?: string;
  page?: number;
  size?: number;
}

/** GET /api/access-log/entries — today's (or given date's) local file logs. */
export const getLogEntries = (query: LocalLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/entries`, {
    ...options,
    params: query as Record<string, unknown>,
  });

/** GET /api/access-log/stats */
export const getLogStats = (
  query: { date?: string },
  options?: RequestOptions,
) => httpClient.get<AccessLogStats>(`${BASE}/stats`, { ...options, params: query });

/* --------------------------------- K8S ----------------------------------- */

export interface K8sLogQuery extends AccessLogQueryFilters {
  clusterId: number;
  namespace?: string;
  podName: string;
  /** Realtime mode: read last N stdout lines (default 500 server-side). */
  tailLines?: number;
  /** History mode: takes precedence over tailLines when set. */
  sinceSeconds?: number;
  page?: number;
  size?: number;
}

/** GET /api/access-log/k8s/namespaces?clusterId=... */
export const getK8sNamespaces = (
  params: { clusterId: number },
  options?: RequestOptions,
) => httpClient.get<string[]>(`${BASE}/k8s/namespaces`, { ...options, params });

/** GET /api/access-log/k8s/pods — gateway pods only. */
export const getK8sPods = (
  params: { clusterId: number; namespace?: string },
  options?: RequestOptions,
) => httpClient.get<GatewayPodInfo[]>(`${BASE}/k8s/pods`, { ...options, params });

/** GET /api/access-log/k8s/entries — parsed logs from pod stdout. */
export const getK8sEntries = (query: K8sLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/k8s/entries`, {
    ...options,
    params: { ...query },
  });

/** GET /api/access-log/k8s/stats */
export const getK8sStats = (
  query: Pick<K8sLogQuery, 'clusterId' | 'namespace' | 'podName' | 'tailLines' | 'sinceSeconds'>,
  options?: RequestOptions,
) => httpClient.get<AccessLogStats>(`${BASE}/k8s/stats`, { ...options, params: query });

/* ------------------------- History (Fluent Bit DB) ------------------------ */

export interface HistoryLogQuery extends AccessLogQueryFilters {
  instanceId?: string;
  routeId?: string;
  /** ISO-8601 instant. */
  startTime: string;
  endTime: string;
  page?: number;
  size?: number;
}

/** GET /api/access-log/history/entries — collected logs from the database. */
export const getHistoryEntries = (query: HistoryLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/history/entries`, {
    ...options,
    params: { ...query },
  });

/** GET /api/access-log/history/stats */
export const getHistoryStats = (
  query: Pick<HistoryLogQuery, 'instanceId' | 'startTime' | 'endTime'>,
  options?: RequestOptions,
) => httpClient.get<AccessLogStats>(`${BASE}/history/stats`, { ...options, params: query });
