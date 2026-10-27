/**
 * Plugin domain types, aligned with gateway-admin:
 * - model/PluginEntity.java
 * - model/PluginBindingEntity.java
 * - controller/PluginController.java (PluginStats)
 * - controller/RoutePluginBindingController.java (BindingRequest)
 * - RoutePluginBindingService (BindingStats)
 *
 * Note: plugin controllers return bare entities (no ApiResponse envelope);
 * httpClient's unwrap logic passes them through unchanged.
 */

/** jarPath === BUILTIN marks plugins bundled with the gateway. */
export const BUILTIN_JAR_PATH = 'BUILTIN';

/** routeId used by global bindings (PluginBindingRepository.GLOBAL_ROUTE_ID). */
export const GLOBAL_BINDING_ROUTE_ID = 'GLOBAL';

export interface Plugin {
  id: number;
  /** UUID business id, referenced by bindings. */
  pluginId: string;
  pluginName: string;
  pluginClass: string;
  configClass?: string;
  /** Backend default: "1.0.0". */
  version: string;
  description?: string;
  descriptionEn?: string;
  /** Backend default: "custom". */
  category: string;
  /** Icon component name, backend default "ThunderboltOutlined". */
  icon?: string;
  /** Backend default "#8b5cf6". */
  color?: string;
  /** JSON text describing config fields (GET /{pluginId}/schema returns it too). */
  configSchema?: string;
  /** "BUILTIN" for built-in plugins, file path for uploaded jars. */
  jarPath?: string;
  jarHash?: string;
  enabled: boolean;
  loaded: boolean;
  registered: boolean;
  instanceId?: string;
  createdAt: string;
  updatedAt: string;
}

/** GET /api/plugins/stats response. */
export interface PluginStats {
  total: number;
  enabled: number;
  disabled: number;
  loaded: number;
  registered: number;
}

export interface PluginBinding {
  id: number;
  /** UUID business id. */
  bindingId: string;
  /** "GLOBAL" for global bindings. */
  routeId: string;
  pluginId: string;
  pluginName: string;
  /** JSON string of the effective config (backend stores json column). */
  config?: string;
  order: number;
  enabled: boolean;
  instanceId?: string;
  createdAt: string;
  updatedAt?: string;
}

/** Binding enriched on the client with the owning route's display name. */
export interface PluginBindingView extends PluginBinding {
  routeName?: string;
}

/** POST/PUT body — controller inner class BindingRequest. */
export interface PluginBindingRequest {
  pluginId?: string;
  config?: Record<string, unknown>;
  order?: number;
  instanceId?: string;
}

/** GET /api/plugin-bindings/stats response. */
export interface PluginBindingStats {
  total: number;
  enabled: number;
  disabled: number;
}

/** One configurable field declared by a plugin's config schema. */
export interface PluginConfigField {
  required?: boolean;
  name: string;
  /** number | switch | select | textarea | string (default). */
  type: string;
  label: string;
  default?: unknown;
  options?: { label: string; value: string }[];
  min?: number;
  max?: number;
  description?: string;
}

/** Schema JSON shape returned by GET /api/plugins/{pluginId}/schema. */
export interface PluginConfigSchema {
  fields: PluginConfigField[];
  hasSubSchemas?: boolean;
}
