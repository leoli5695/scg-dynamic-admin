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
