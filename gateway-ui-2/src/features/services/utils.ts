/**
 * Services feature helpers — status derivation and validation.
 */
import type { ServiceDefinition } from '@/types/service';

export const IPV4_REGEX = /^(\d{1,3}\.){3}\d{1,3}$/;

export const isValidIP = (ip: string): boolean =>
  !!ip && IPV4_REGEX.test(ip) && ip.split('.').map(Number).every((o) => o >= 0 && o <= 255);

export const isValidPort = (port: number): boolean =>
  Number.isInteger(port) && port > 0 && port < 65536;

export type ServiceBadgeColor = 'success' | 'error' | 'warning' | 'default';

export interface ServiceStatus {
  color: ServiceBadgeColor;
  /** i18n key */
  textKey: string;
  /** Interpolation params for status_partial. */
  params?: Record<string, number>;
}

/** Derive the aggregate status of a service from its instances. */
export const getServiceStatus = (service: ServiceDefinition): ServiceStatus => {
  const instances = service.instances || [];
  if (instances.length === 0) {
    return { color: 'default', textKey: 'services.status_no_instances' };
  }

  const enabled = instances.filter((i) => i.enabled !== false);
  const disabledCount = instances.length - enabled.length;
  const healthyEnabled = enabled.filter((i) => i.healthy !== false).length;

  if (disabledCount === instances.length) {
    return { color: 'default', textKey: 'services.status_all_disabled' };
  }
  if (enabled.length > 0 && healthyEnabled === 0) {
    return { color: 'error', textKey: 'services.status_all_down' };
  }
  if (healthyEnabled === enabled.length && disabledCount === 0) {
    return { color: 'success', textKey: 'services.status_healthy' };
  }
  return {
    color: 'warning',
    textKey: 'services.status_partial',
    params: { healthy: healthyEnabled, total: instances.length },
  };
};

/** Load-balancer strategy → i18n key. */
export const getLoadBalancerLabelKey = (lb: string): string => {
  switch (lb) {
    case 'round-robin': return 'services.round_robin';
    case 'random': return 'services.random';
    case 'weighted': return 'services.weighted';
    case 'consistent-hash': return 'services.consistent_hash';
    default: return lb;
  }
};

/**
 * Everything a catalog row renders, flattened to a string. A refresh returns
 * fresh objects even when nothing changed; memoising on this instead of object
 * identity keeps unchanged rows from re-rendering.
 */
export const serviceSignature = (s: ServiceDefinition): string => {
  const instances = (s.instances || [])
    .map((i) => `${i.ip}:${i.port}:${i.weight ?? 1}:${i.healthy !== false ? 1 : 0}:${i.enabled !== false ? 1 : 0}`)
    .join(',');
  const ph = s.providerHealth;
  return [
    s.name, s.serviceId ?? '', s.description ?? '', s.loadBalancer ?? '',
    s.category ?? '', s.providerType ?? '', s.baseUrl ?? '', instances,
    ph ? `${ph.status}:${ph.lastProbeAt ?? ''}:${ph.message ?? ''}` : '-',
  ].join('|');
};

export interface ServiceCatalogRow {
  service: ServiceDefinition;
  sig: string;
}

export type CatalogHealth = 'healthy' | 'unhealthy' | 'unknown';

/**
 * Health means different things per category:
 *  - NORMAL: instance heartbeat (unchanged semantics — a service with no healthy
 *    instance counts as unhealthy).
 *  - AI: the backend's provider probe. A missing providerHealth key is UNKNOWN,
 *    never unhealthy, and instance heartbeat is never used as a stand-in.
 */
export const catalogHealth = (s: ServiceDefinition): CatalogHealth => {
  if (s.category === 'AI') {
    const status = s.providerHealth?.status;
    if (status === 'HEALTHY') return 'healthy';
    if (status === 'UNHEALTHY') return 'unhealthy';
    return 'unknown';
  }
  return s.instances && s.instances.some((i) => i.healthy !== false) ? 'healthy' : 'unhealthy';
};

export interface CatalogStatus {
  tone: 'ok' | 'warn' | 'err' | 'idle';
  textKey: string;
  params?: Record<string, number>;
  /** Probe summary for AI services; already redacted by the backend. */
  detail?: string | null;
  lastProbeAt?: number | null;
}

/** Badge model for a catalog row, honouring the per-category health source. */
export const getCatalogStatus = (s: ServiceDefinition): CatalogStatus => {
  if (s.category === 'AI') {
    const health = catalogHealth(s);
    const ph = s.providerHealth;
    return {
      tone: health === 'healthy' ? 'ok' : health === 'unhealthy' ? 'err' : 'idle',
      textKey: health === 'healthy'
        ? 'services.healthy'
        : health === 'unhealthy' ? 'services.unhealthy' : 'services.not_probed',
      detail: ph?.message ?? null,
      lastProbeAt: ph?.lastProbeAt ?? null,
    };
  }
  const legacy = getServiceStatus(s);
  const tone = { success: 'ok', warning: 'warn', error: 'err', default: 'idle' } as const;
  return { tone: tone[legacy.color], textKey: legacy.textKey, params: legacy.params };
};
