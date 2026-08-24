/**
 * Service domain types.
 *
 * Field-aligned with backend sources of truth:
 * - model/ServiceDefinition.java (create/update bodies, GET /{name})
 * - controller/ServiceController.java#getAllServices (list response is a
 *   hand-built map that additionally injects "healthy" per instance)
 * - model/NacosDiscoveryServiceInfo.java (GET /nacos-discovery)
 */

/** One upstream instance of a service. */
export interface ServiceInstanceItem {
  /** Defaults to "ip:port" when created via the constructor path. */
  instanceId?: string;
  ip: string;
  port: number;
  weight?: number;
  enabled?: boolean;
  /** Injected by GET /api/services from the health repository. */
  healthy?: boolean;
  metadata?: Record<string, string>;
}

/** Load balancer strategies accepted by the gateway. */
export type LoadBalancerType = 'weighted' | 'round-robin' | 'random' | 'consistent-hash';

/** Service as stored/returned by the admin backend. */
export interface ServiceDefinition {
  name: string;
  /** UUID, display only (transient on the backend). */
  serviceId?: string;
  /** UI-only description (transient on the backend, not pushed to Nacos). */
  description?: string;
  loadBalancer?: string;
  instances?: ServiceInstanceItem[];
}

/** GET /api/services/nacos-discovery item. */
export interface NacosDiscoveryServiceInfo {
  serviceName: string;
  namespace?: string;
  group?: string;
  instanceCount?: number;
  /** Computed by backend getter getDisplayName(). */
  displayName?: string;
}

/** PUT /{name}/instances/{instanceId}/status body. */
export interface ServiceInstanceStatusUpdate {
  enabled?: boolean;
  healthy?: boolean;
}
