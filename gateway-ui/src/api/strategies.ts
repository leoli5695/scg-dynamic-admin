/**
 * Strategy API.
 * Endpoints aligned with controller/StrategyController.java (/api/strategies)
 * and controller/StrategyTypeController.java (/api/strategy-types).
 * All write operations accept an optional instanceId query param for
 * configuration isolation.
 */
import httpClient, { RequestOptions } from './http';
import type { StrategyDefinition, StrategyTypeInfo } from '../types/strategy';

const BASE = '/api/strategies';
const TYPES_BASE = '/api/strategy-types';

/** GET /api/strategies — optionally filtered by gateway instance UUID. */
export const listStrategies = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<StrategyDefinition[]>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/strategies/{id} */
export const getStrategyById = (id: string, options?: RequestOptions) =>
  httpClient.get<StrategyDefinition>(`${BASE}/${encodeURIComponent(id)}`, options);

/** POST /api/strategies — body follows model/StrategyDefinition. */
export const createStrategy = (
  body: StrategyDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<StrategyDefinition>(BASE, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** PUT /api/strategies/{id} */
export const updateStrategy = (
  id: string,
  body: StrategyDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.put<StrategyDefinition>(`${BASE}/${encodeURIComponent(id)}`, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** DELETE /api/strategies/{id} */
export const deleteStrategy = (
  id: string,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.delete<void>(`${BASE}/${encodeURIComponent(id)}`, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** POST /api/strategies/{id}/enable */
export const enableStrategy = (
  id: string,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/${encodeURIComponent(id)}/enable`, undefined, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** POST /api/strategies/{id}/disable */
export const disableStrategy = (
  id: string,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/${encodeURIComponent(id)}/disable`, undefined, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/**
 * GET /api/strategy-types/enabled — enabled strategy type metadata with
 * enriched configSchema (AUTH / TOKEN_RATE_LIMITER subSchemas included).
 */
export const listEnabledStrategyTypes = (options?: RequestOptions) =>
  httpClient.get<StrategyTypeInfo[]>(`${TYPES_BASE}/enabled`, options);
