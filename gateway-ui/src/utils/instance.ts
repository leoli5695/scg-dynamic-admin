/**
 * Instance Utility Functions
 * Shared functions for instance status and configuration handling
 */

/**
 * Instance status codes - matches backend enum
 */
export const INSTANCE_STATUS = {
  STARTING: 0,
  RUNNING: 1,
  ERROR: 2,
  STOPPING: 3,
  STOPPED: 4,
} as const;

export type InstanceStatusCode = typeof INSTANCE_STATUS[keyof typeof INSTANCE_STATUS];

/**
 * Get status color for Ant Design Tag component
 * @param statusCode - Instance status code
 * @returns Ant Design color string
 */
export const getStatusColor = (statusCode: InstanceStatusCode): string => {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING:
      return 'green';
    case INSTANCE_STATUS.STARTING:
      return 'blue';
    case INSTANCE_STATUS.STOPPED:
      return 'orange';
    case INSTANCE_STATUS.ERROR:
      return 'red';
    case INSTANCE_STATUS.STOPPING:
      return 'purple';
    default:
      return 'default';
  }
};

/**
 * Get status text based on status code
 * @param statusCode - Instance status code
 * @param status - Raw status string fallback
 * @param t - i18next translation function
 * @returns Localized status text
 */
export const getStatusText = (
  statusCode: InstanceStatusCode,
  status: string,
  t: (key: string) => string
): string => {
  switch (statusCode) {
    case INSTANCE_STATUS.STARTING:
      return t('instance.status_starting');
    case INSTANCE_STATUS.RUNNING:
      return t('instance.status_running');
    case INSTANCE_STATUS.ERROR:
      return t('instance.status_error');
    case INSTANCE_STATUS.STOPPING:
      return t('instance.status_stopping');
    case INSTANCE_STATUS.STOPPED:
      return t('instance.status_stopped');
    default:
      return status;
  }
};

/**
 * Get status color by status name (string)
 * @param status - Status name string
 * @returns Ant Design color string
 */
export const getStatusColorByName = (status: string): string => {
  switch (status) {
    case 'Running':
    case 'RUNNING':
      return 'green';
    case 'Creating':
    case 'Starting':
      return 'blue';
    case 'Stopped':
    case 'STOPPED':
      return 'orange';
    case 'Error':
    case 'ERROR':
      return 'red';
    case 'Stopping':
      return 'purple';
    default:
      return 'default';
  }
};

/**
 * Get status text by status name (string)
 * @param status - Status name string
 * @param t - i18next translation function
 * @returns Localized status text
 */
export const getStatusTextByName = (status: string, t: (key: string) => string): string => {
  switch (status) {
    case 'Running':
    case 'RUNNING':
      return t('instance.status_running');
    case 'Creating':
      return t('instance.status_creating');
    case 'Starting':
      return t('instance.status_starting');
    case 'Stopped':
    case 'STOPPED':
      return t('instance.status_stopped');
    case 'Error':
    case 'ERROR':
      return t('instance.status_error');
    case 'Stopping':
      return t('instance.status_stopping');
    default:
      return status;
  }
};

/**
 * Instance configuration interface
 */
export interface InstanceConfig {
  specType: string;
  cpuCores: number;
  memoryMB: number;
}

/**
 * Get spec text based on instance configuration
 * @param instance - Instance configuration object
 * @param t - i18next translation function
 * @returns Spec description text
 */
export const getSpecText = (
  instance: InstanceConfig,
  t: (key: string) => string
): string => {
  if (instance.specType === 'custom') {
    return `${instance.cpuCores}C ${instance.memoryMB}MB`;
  }
  const specKey = `instance.spec_${instance.specType}`;
  return t(specKey);
};

/**
 * Instance with access URL fields
 */
export interface InstanceWithAccessUrl {
  manualAccessUrl?: string;
  discoveredAccessUrl?: string;
  reportedAccessUrl?: string;
  nodeIp?: string;
  serverPort?: number;
  nodePort?: number;
}

/**
 * Get effective access URL with priority:
 * 1. Manual configured (highest priority, for SLB/custom domain)
 * 2. K8s discovered (LoadBalancer IP or NodePort)
 * 3. Heartbeat reported (local dev, ECS direct)
 * 4. Default: nodeIp:serverPort
 */
export const getEffectiveAccessUrl = (instance: InstanceWithAccessUrl): string | null => {
  // 1. Manual configured (highest priority)
  if (instance.manualAccessUrl) return instance.manualAccessUrl;
  // 2. K8s discovered
  if (instance.discoveredAccessUrl) return instance.discoveredAccessUrl;
  // 3. Heartbeat reported
  if (instance.reportedAccessUrl) return instance.reportedAccessUrl;
  // 4. Default: nodeIp:serverPort or nodeIp:nodePort
  if (instance.nodeIp && instance.serverPort) {
    return `http://${instance.nodeIp}:${instance.serverPort}`;
  }
  if (instance.nodeIp && instance.nodePort) {
    return `http://${instance.nodeIp}:${instance.nodePort}`;
  }
  return null;
};

/**
 * Instance with Nacos server address
 */
export interface InstanceWithNacos {
  nacosServerAddr?: string;
}

/**
 * Get effective Nacos server address
 * @param instance - Instance with nacosServerAddr field
 * @returns Nacos server address (default: 127.0.0.1:8848)
 */
export const getEffectiveNacosServerAddr = (instance: InstanceWithNacos): string => {
  return instance.nacosServerAddr || '127.0.0.1:8848';
};

export default {
  INSTANCE_STATUS,
  getStatusColor,
  getStatusText,
  getStatusColorByName,
  getStatusTextByName,
  getSpecText,
  getEffectiveAccessUrl,
  getEffectiveNacosServerAddr,
};