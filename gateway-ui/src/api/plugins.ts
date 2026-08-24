/**
 * Plugin API.
 * Endpoints aligned with controller/PluginController.java (/api/plugins).
 *
 * Notes (per backend signatures):
 * - Responses are bare entities / lists — no ApiResponse envelope.
 * - PUT /{pluginId} reads @RequestParam, so metadata goes through query params.
 * - POST /upload is multipart (file part) with instanceId as query param.
 * - GET /{pluginId}/schema returns the schema as a JSON *string*.
 */
import httpClient, { RequestOptions } from './http';
import type { Plugin, PluginStats } from '../types/plugin';

const BASE = '/api/plugins';

/** GET /api/plugins?instanceId= */
export const listPlugins = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<Plugin[]>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/plugins/{pluginId} */
export const getPlugin = (pluginId: string, options?: RequestOptions) =>
  httpClient.get<Plugin>(`${BASE}/${pluginId}`, options);

/** POST /api/plugins/upload — multipart jar upload, instanceId via query. */
export const uploadPlugin = (
  file: File,
  instanceId?: string,
  options?: RequestOptions,
) => {
  const formData = new FormData();
  formData.append('file', file);
  return httpClient.post<Plugin>(`${BASE}/upload`, formData, {
    ...options,
    params: instanceId ? { instanceId } : undefined,
  });
};

/** PUT /api/plugins/{pluginId} — @RequestParam metadata update. */
export const updatePlugin = (
  pluginId: string,
  body: {
    description?: string;
    descriptionEn?: string;
    category?: string;
    icon?: string;
    color?: string;
  },
  options?: RequestOptions,
) => httpClient.put<Plugin>(`${BASE}/${pluginId}`, undefined, {
  ...options,
  params: body as Record<string, unknown>,
});

/** DELETE /api/plugins/{pluginId} → 204 */
export const deletePlugin = (pluginId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${pluginId}`, options);

/** POST /api/plugins/{pluginId}/enable */
export const enablePlugin = (pluginId: string, options?: RequestOptions) =>
  httpClient.post<Plugin>(`${BASE}/${pluginId}/enable`, undefined, options);

/** POST /api/plugins/{pluginId}/disable */
export const disablePlugin = (pluginId: string, options?: RequestOptions) =>
  httpClient.post<Plugin>(`${BASE}/${pluginId}/disable`, undefined, options);

/** GET /api/plugins/stats?instanceId= */
export const getPluginStats = (
  params?: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<PluginStats>(`${BASE}/stats`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/plugins/{pluginId}/schema — returns JSON text. */
export const getPluginSchema = (pluginId: string, options?: RequestOptions) =>
  httpClient.get<string>(`${BASE}/${pluginId}/schema`, options);

/** GET /api/plugins/{pluginId}/routes — route ids bound to the plugin. */
export const listPluginBoundRoutes = (
  pluginId: string,
  options?: RequestOptions,
) => httpClient.get<string[]>(`${BASE}/${pluginId}/routes`, options);
