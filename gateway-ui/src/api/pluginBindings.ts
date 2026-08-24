/**
 * Plugin binding API.
 * Endpoints aligned with controller/RoutePluginBindingController.java.
 *
 * Notes (per backend signatures):
 * - Responses are bare entities / lists — no ApiResponse envelope.
 * - Route-scoped endpoints live under /api/routes/{routeId}/plugins.
 * - Global bindings use routeId "GLOBAL" server-side.
 * - GET /api/plugin-bindings/global has no instance filter — callers that
 *   need instance isolation must filter by binding.instanceId themselves.
 */
import httpClient, { RequestOptions } from './http';
import type {
  PluginBinding,
  PluginBindingRequest,
  PluginBindingStats,
} from '../types/plugin';

const GLOBAL_BASE = '/api/plugin-bindings/global';

// ==================== Route-scoped bindings ====================

/** GET /api/routes/{routeId}/plugins */
export const listRouteBindings = (
  routeId: string,
  options?: RequestOptions,
) => httpClient.get<PluginBinding[]>(`/api/routes/${routeId}/plugins`, options);

/** POST /api/routes/{routeId}/plugins — body BindingRequest. */
export const createRouteBinding = (
  routeId: string,
  body: PluginBindingRequest,
  options?: RequestOptions,
) => httpClient.post<PluginBinding>(`/api/routes/${routeId}/plugins`, body, options);

/** PUT /api/routes/{routeId}/plugins/{bindingId} — only config/order applied. */
export const updateRouteBinding = (
  routeId: string,
  bindingId: string,
  body: PluginBindingRequest,
  options?: RequestOptions,
) => httpClient.put<PluginBinding>(
  `/api/routes/${routeId}/plugins/${bindingId}`,
  body,
  options,
);

/** DELETE /api/routes/{routeId}/plugins/{bindingId} → 204 */
export const deleteRouteBinding = (
  routeId: string,
  bindingId: string,
  options?: RequestOptions,
) => httpClient.delete<void>(
  `/api/routes/${routeId}/plugins/${bindingId}`,
  options,
);

/** POST /api/routes/{routeId}/plugins/{bindingId}/enable|disable */
export const setRouteBindingEnabled = (
  routeId: string,
  bindingId: string,
  enabled: boolean,
  options?: RequestOptions,
) => httpClient.post<PluginBinding>(
  `/api/routes/${routeId}/plugins/${bindingId}/${enabled ? 'enable' : 'disable'}`,
  undefined,
  options,
);

// ==================== Global bindings ====================

/** GET /api/plugin-bindings/global — ALL instances, filter client-side. */
export const listGlobalBindings = (options?: RequestOptions) =>
  httpClient.get<PluginBinding[]>(GLOBAL_BASE, options);

/** POST /api/plugin-bindings/global — body BindingRequest (incl. instanceId). */
export const createGlobalBinding = (
  body: PluginBindingRequest,
  options?: RequestOptions,
) => httpClient.post<PluginBinding>(GLOBAL_BASE, body, options);

/** PUT /api/plugin-bindings/global/{bindingId} — only config/order applied. */
export const updateGlobalBinding = (
  bindingId: string,
  body: PluginBindingRequest,
  options?: RequestOptions,
) => httpClient.put<PluginBinding>(`${GLOBAL_BASE}/${bindingId}`, body, options);

/** DELETE /api/plugin-bindings/global/{bindingId} → 204 */
export const deleteGlobalBinding = (bindingId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${GLOBAL_BASE}/${bindingId}`, options);

/** POST /api/plugin-bindings/global/{bindingId}/enable|disable */
export const setGlobalBindingEnabled = (
  bindingId: string,
  enabled: boolean,
  options?: RequestOptions,
) => httpClient.post<PluginBinding>(
  `${GLOBAL_BASE}/${bindingId}/${enabled ? 'enable' : 'disable'}`,
  undefined,
  options,
);

// ==================== Stats ====================

/** GET /api/plugin-bindings/stats — global counters (all instances). */
export const getBindingStats = (options?: RequestOptions) =>
  httpClient.get<PluginBindingStats>('/api/plugin-bindings/stats', options);
