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

/** Service category discriminator. AI services are LLM providers bound by AI routes. */
export type ServiceCategory = 'NORMAL' | 'AI';

/**
 * LLM provider types an AI service can be. Mirrors the gateway's built-in provider
 * catalogue (LlmProvider enum ids) — the data plane resolves the provider by model
 * prefix against exactly these ids, so a relay / self-hosted backend is expressed as a
 * real provider type + a baseUrl override rather than a separate "custom" type.
 */
export const AI_PROVIDER_TYPES = [
  'qwen', 'openai', 'deepseek', 'zhipu', 'moonshot', 'anthropic',
] as const;
export type AiProviderType = typeof AI_PROVIDER_TYPES[number];

/** Service as stored/returned by the admin backend. */
export interface ServiceDefinition {
  name: string;
  /** UUID, display only (transient on the backend). */
  serviceId?: string;
  /** UI-only description (transient on the backend, not pushed to Nacos). */
  description?: string;
  loadBalancer?: string;
  instances?: ServiceInstanceItem[];
  /** NORMAL (default) or AI. */
  category?: ServiceCategory;
  // ---- AI-service-only fields (category === 'AI') ----
  /** LLM provider type, e.g. qwen/openai/deepseek. */
  providerType?: string;
  /** Wire protocol; defaults to OpenAI-compatible passthrough. */
  protocol?: string;
  /** Base URL override (relay / egress proxy / self-hosted). */
  baseUrl?: string;
  /** Reference into the encrypted credential store. Read-only from the backend. */
  credentialRef?: string;
  /**
   * Plaintext API key input for create/rotate. Write-only: never returned by the
   * backend, never persisted client-side.
   */
  apiKey?: string;
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
