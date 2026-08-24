/**
 * Route API.
 * Endpoints aligned with controller/RouteController.java (/api/routes).
 */
import httpClient, { RequestOptions } from './http';
import type { RouteDefinition, RouteResponse } from '../types/route';

const BASE = '/api/routes';

/** GET /api/routes — optionally filtered by gateway instance UUID. */
export const listRoutes = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<RouteResponse[]>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/routes/{id} */
export const getRouteById = (id: string, options?: RequestOptions) =>
  httpClient.get<RouteResponse>(`${BASE}/${encodeURIComponent(id)}`, options);

/** POST /api/routes — create; body follows model/RouteDefinition. */
export const createRoute = (
  body: RouteDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<RouteResponse>(BASE, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** PUT /api/routes/{id} — id must be the route UUID. */
export const updateRoute = (
  id: string,
  body: RouteDefinition,
  options?: RequestOptions,
) => httpClient.put<RouteDefinition>(`${BASE}/${encodeURIComponent(id)}`, body, options);

/** DELETE /api/routes/{id} */
export const deleteRoute = (id: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${encodeURIComponent(id)}`, options);

/** POST /api/routes/{id}/enable */
export const enableRoute = (id: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/${encodeURIComponent(id)}/enable`, undefined, options);

/** POST /api/routes/{id}/disable */
export const disableRoute = (id: string, options?: RequestOptions) =>
  httpClient.post<void>(`${BASE}/${encodeURIComponent(id)}/disable`, undefined, options);
