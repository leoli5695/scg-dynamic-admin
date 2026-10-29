/**
 * Token 计量可观测 API（gateway-admin /api/token-usage）。
 * 维度过滤留空即不过该维；时间缺省后端取最近 24 小时。
 */
import httpClient, { RequestOptions } from './http';

const BASE = '/api/token-usage';

export interface TokenUsageCorrection {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  chargedTokens: number;
  monthlyDelta: number;
  dailyDelta: number;
  reason: string;
}
export interface TokenUsageReconciliation {
  id: string;
  status: 'PENDING' | 'APPLIED' | 'REVIEW_REQUIRED';
  operator: string;
  reason: string;
  monthly_delta: number;
  daily_delta: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  charged_tokens: number;
  original_usage: string;
  last_error: string | null;
  attempts: number;
}
const reconciliationUrl = (usageId: number) => `${BASE}/events/${usageId}/reconciliation`;
export const getTokenUsageReconciliation = (usageId: number, options?: RequestOptions) =>
  httpClient.get<TokenUsageReconciliation | null>(reconciliationUrl(usageId), options);
export const reconcileTokenUsage = (usageId: number, correction: TokenUsageCorrection) =>
  httpClient.post<TokenUsageReconciliation>(reconciliationUrl(usageId), correction);
export const retryTokenUsageReconciliation = (usageId: number) =>
  httpClient.post<TokenUsageReconciliation>(`${reconciliationUrl(usageId)}/retry`);

export interface TokenUsageFilters {
  unresolvedOnly?: boolean;
  from?: string;
  to?: string;
  instanceId?: string;
  routeId?: string;
  tenantId?: string;
  model?: string;
}

export interface TokenUsageOverview {
  unknownUsageRequests?: number;
  failedSettlementRequests?: number;
  requests: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  avgLatencyMs: number;
  activeTenants: number;
  activeRoutes: number;
  activeModels: number;
}

export interface TokenUsageBucket {
  bucket: string;
  requests: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface TokenUsageBreakdownRow {
  key: string;
  requests: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  avgLatencyMs: number;
  lastSeen: string | null;
}

export interface TokenUsageEvent {
  usageStatus?: 'CONFIRMED' | 'ESTIMATED' | 'UNKNOWN' | 'NOT_BILLED' | 'LEGACY' | 'RECONCILED';
  eventId?: string | null;
  quotaMonth?: string | null;
  quotaDay?: string | null;
  quotaPeriod?: string | null;
  quotaLedgerId?: string | null;
  quotaLedgerState?: 'PENDING' | 'READY' | null;
  settlementStatus?: string;
  provider?: string | null;
  serviceId?: string | null;
  chargedTokens?: number | null;
  reservedTokens?: number | null;
  id: number;
  requestTime: string | null;
  tenantId: string;
  routeId: string | null;
  model: string | null;
  responseFormat: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  latencyMs: number | null;
  traceId: string | null;
  instanceId: string | null;
}

const qs = (filters: TokenUsageFilters, extra: Record<string, string | number> = {}) => {
  const params = new URLSearchParams();
  Object.entries({ ...filters, ...extra }).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  });
  const s = params.toString();
  return s ? `?${s}` : '';
};

/** 总量卡。 */
export const getTokenUsageOverview = (filters: TokenUsageFilters, options?: RequestOptions) =>
  httpClient.get<TokenUsageOverview>(`${BASE}/overview${qs(filters)}`, options);

/** 时序桶（hour|day）。 */
export const getTokenUsageTimeseries = (
  filters: TokenUsageFilters,
  granularity: 'hour' | 'day',
  options?: RequestOptions,
) =>
  httpClient.get<{ granularity: string; series: TokenUsageBucket[] }>(
    `${BASE}/timeseries${qs(filters, { granularity })}`, options);

/** 分组榜（tenant|route|model|instance）。 */
export const getTokenUsageBreakdown = (
  filters: TokenUsageFilters,
  dimension: 'tenant' | 'route' | 'model' | 'instance',
  limit = 10,
  options?: RequestOptions,
) =>
  httpClient.get<{ dimension: string; rows: TokenUsageBreakdownRow[] }>(
    `${BASE}/breakdown${qs(filters, { dimension, limit })}`, options);

/** 明细分页（时间倒序）。 */
export const getTokenUsageEvents = (
  filters: TokenUsageFilters,
  page: number,
  size: number,
  options?: RequestOptions,
) =>
  httpClient.get<{ content: TokenUsageEvent[]; total: number; page: number; size: number }>(
    `${BASE}/events${qs(filters, { page, size })}`, options);

/** 租户下拉数据源：配额表里的租户（计量页过滤用）。 */
export interface TokenQuotaTenant {
  tenantId: string;
}
export const getTokenQuotaTenants = (options?: RequestOptions) =>
  httpClient.get<TokenQuotaTenant[]>('/api/token-quota', options);

/** Current Redis-backed quota usage for one tenant (absent when the tenant has no quota). */
export interface TokenQuotaStatus {
  tenantId: string;
  enabled?: boolean;
  monthlyQuota?: number;
  monthlyUsed?: number;
  monthlyRemaining?: number;
  monthlyUsagePercent?: number;
  dailyQuota?: number;
  dailyUsed?: number;
  dailyRemaining?: number;
  dailyUsagePercent?: number;
  alertStatus?: string;
  periodStart?: string;
  periodEnd?: string;
}
export const getTokenQuotaStatus = (tenantId: string, options?: RequestOptions) =>
  httpClient.get<TokenQuotaStatus>(`/api/token-quota/${encodeURIComponent(tenantId)}/status`, options);
