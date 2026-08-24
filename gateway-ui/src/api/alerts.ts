/**
 * Alert API — endpoints aligned with controller/AlertController.java
 * (/api/alerts). Standard ApiResponse envelope, auto-unwrapped by httpClient;
 * non-200 business codes surface as ApiError.
 */
import httpClient, { RequestOptions } from './http';
import type {
  AlertConfigData,
  AlertConfigSaveRequest,
  AlertConfigSaveResult,
  AlertHistoryPage,
  AlertThresholdMetrics,
  SendTestEmailRequest,
} from '../types/alert';

const BASE = '/api/alerts';

/** GET /api/alerts/config — config for the instance, or the active/default one. */
export const getAlertConfig = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.get<AlertConfigData>(`${BASE}/config`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** POST /api/alerts/config — create or update (id present = update). */
export const saveAlertConfig = (body: AlertConfigSaveRequest, options?: RequestOptions) =>
  httpClient.post<AlertConfigSaveResult>(`${BASE}/config`, body, options);

/** PUT /api/alerts/config/{id} — explicit update by id. */
export const updateAlertConfig = (id: number, body: AlertConfigSaveRequest, options?: RequestOptions) =>
  httpClient.put<AlertConfigSaveResult>(`${BASE}/config/${id}`, body, options);

/** GET /api/alerts/history — paged alert history (newest first). */
export const getAlertHistory = (
  params: { instanceId?: string; page?: number; size?: number } = {},
  options?: RequestOptions,
) => httpClient.get<AlertHistoryPage>(`${BASE}/history`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** DELETE /api/alerts/history — clear history (optionally per instance). */
export const clearAlertHistory = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.delete<unknown>(`${BASE}/history`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** POST /api/alerts/test — send a test email to the given recipients. */
export const sendTestEmail = (body: SendTestEmailRequest, options?: RequestOptions) =>
  httpClient.post<unknown>(`${BASE}/test`, body, options);

/** GET /api/alerts/thresholds/metrics — current metrics for threshold display. */
export const getAlertThresholdMetrics = (options?: RequestOptions) =>
  httpClient.get<AlertThresholdMetrics>(`${BASE}/thresholds/metrics`, options);
