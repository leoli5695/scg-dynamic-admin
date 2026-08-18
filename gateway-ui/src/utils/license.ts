/**
 * JWT Token utility functions for parsing license info.
 */

/**
 * Parse JWT token and extract claims without verification.
 * Note: This is for frontend display only, backend verification is required.
 */
export function parseJwtToken(token: string): Record<string, any> | null {
  if (!token) return null;

  try {
    // JWT format: header.payload.signature
    const parts = token.split('.');
    if (parts.length !== 3) return null;

    // Decode payload (Base64Url)
    const payload = parts[1];
    const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decoded);
  } catch (e) {
    console.error('Failed to parse JWT token:', e);
    return null;
  }
}

/**
 * Get license edition from JWT token.
 * Returns 'BASIC' if not present or token is invalid.
 */
export function getLicenseEditionFromToken(token: string): string {
  const payload = parseJwtToken(token);
  if (!payload) return 'BASIC';

  const edition = payload['licenseEdition'];
  if (!edition) return 'BASIC';

  return edition.toUpperCase();
}

/**
 * Get username from JWT token.
 */
export function getUsernameFromToken(token: string): string | null {
  const payload = parseJwtToken(token);
  if (!payload) return null;

  return payload['sub'] || payload['username'] || null;
}

/**
 * Get roles from JWT token.
 */
export function getRolesFromToken(token: string): string[] {
  const payload = parseJwtToken(token);
  if (!payload) return [];

  const roles = payload['roles'];
  if (!roles || !Array.isArray(roles)) return [];

  return roles;
}

/**
 * Check if token is expired.
 */
export function isTokenExpired(token: string): boolean {
  const payload = parseJwtToken(token);
  if (!payload) return true;

  const exp = payload['exp'];
  if (!exp) return false;

  // exp is in seconds, Date.now() is in milliseconds
  const expirationTime = exp * 1000;
  return Date.now() >= expirationTime;
}

/**
 * License edition display configuration.
 */
export const LICENSE_EDITION_CONFIG = {
  BASIC: {
    label: '基础版',
    color: 'default',
    features: ['BASIC_ROUTING', 'LOCAL_RATE_LIMIT'] as string[],
    requiredFeatures: {
      WEIGHTED_ROUTING: 'ADVANCED',
      DISTRIBUTED_RATE_LIMIT: 'ADVANCED',
      CIRCUIT_BREAKER: 'ADVANCED',
      K8S_INTEGRATION: 'ADVANCED',
      GLOBAL_CACHE: 'ADVANCED',
      AI_COPilot: 'ENTERPRISE',
      STRESS_TEST: 'ENTERPRISE',
      MULTI_TENANT: 'ENTERPRISE',
      ADVANCED_AUDIT: 'ENTERPRISE',
      ADVANCED_METRICS: 'ENTERPRISE',
      PLUGIN_SYSTEM: 'ENTERPRISE',
      FULL_GRAY_RELEASE: 'ENTERPRISE',
    },
  },
  ADVANCED: {
    label: '高级版',
    color: 'blue',
    features: [
      'BASIC_ROUTING',
      'LOCAL_RATE_LIMIT',
      'WEIGHTED_ROUTING',
      'DISTRIBUTED_RATE_LIMIT',
      'CIRCUIT_BREAKER',
      'K8S_INTEGRATION',
      'GLOBAL_CACHE',
    ] as string[],
    requiredFeatures: {
      AI_COPilot: 'ENTERPRISE',
      STRESS_TEST: 'ENTERPRISE',
      MULTI_TENANT: 'ENTERPRISE',
      ADVANCED_AUDIT: 'ENTERPRISE',
      ADVANCED_METRICS: 'ENTERPRISE',
      PLUGIN_SYSTEM: 'ENTERPRISE',
      FULL_GRAY_RELEASE: 'ENTERPRISE',
    },
  },
  ENTERPRISE: {
    label: '企业版',
    color: 'gold',
    features: [
      'BASIC_ROUTING',
      'LOCAL_RATE_LIMIT',
      'WEIGHTED_ROUTING',
      'DISTRIBUTED_RATE_LIMIT',
      'CIRCUIT_BREAKER',
      'K8S_INTEGRATION',
      'GLOBAL_CACHE',
      'AI_COPilot',
      'STRESS_TEST',
      'MULTI_TENANT',
      'ADVANCED_AUDIT',
      'ADVANCED_METRICS',
      'PLUGIN_SYSTEM',
      'FULL_GRAY_RELEASE',
    ] as string[],
    requiredFeatures: {},
  },
} as const;

/**
 * Check if a feature is available for current edition.
 */
export function isFeatureAvailable(
  featureName: string,
  edition: "BASIC" | "ADVANCED" | "ENTERPRISE"
): boolean {
  const config = LICENSE_EDITION_CONFIG[edition];
  if (!config) return false;

  return config.features.includes(featureName);
}

/**
 * Get required edition for a feature.
 * Returns null if feature is available in current edition.
 */
export function getRequiredEdition(
  featureName: string,
  currentEdition: "BASIC" | "ADVANCED" | "ENTERPRISE"
): string | null {
  const config = LICENSE_EDITION_CONFIG[currentEdition];
  if (!config) return 'ADVANCED';

  // Check if feature is already available
  if (config.features.includes(featureName)) return null;

  // Check required edition
  const required = config.requiredFeatures[featureName as keyof typeof config.requiredFeatures];
  return required || null;
}

/**
 * Feature display names in Chinese.
 */
export const FEATURE_DISPLAY_NAMES: Record<string, string> = {
  BASIC_ROUTING: '基础路由',
  LOCAL_RATE_LIMIT: '本地限流',
  WEIGHTED_ROUTING: '多服务加权路由',
  DISTRIBUTED_RATE_LIMIT: '分布式限流',
  CIRCUIT_BREAKER: '熔断器',
  K8S_INTEGRATION: 'Kubernetes 集成',
  GLOBAL_CACHE: '全局缓存',
  AI_COPilot: 'AI Copilot',
  STRESS_TEST: '一键压测',
  MULTI_TENANT: '多租户隔离',
  ADVANCED_AUDIT: '高级审计',
  ADVANCED_METRICS: '高级监控指标',
  PLUGIN_SYSTEM: '插件系统',
  FULL_GRAY_RELEASE: '完整灰度发布',
};

/**
 * Get feature display name.
 */
export function getFeatureDisplayName(featureName: string): string {
  return FEATURE_DISPLAY_NAMES[featureName] || featureName;
}

/**
 * Edition display names.
 */
export const EDITION_DISPLAY_NAMES: Record<string, string> = {
  BASIC: '基础版',
  ADVANCED: '高级版',
  ENTERPRISE: '企业版',
};

/**
 * Get edition display name.
 */
export function getEditionDisplayName(edition: string): string {
  return EDITION_DISPLAY_NAMES[edition.toUpperCase()] || edition;
}

/**
 * Format license upgrade message for UI display.
 */
export function formatUpgradeMessage(
  featureName: string,
  requiredEdition: string
): string {
  const featureDisplay = getFeatureDisplayName(featureName);
  const editionDisplay = getEditionDisplayName(requiredEdition);
  return `${featureDisplay} 功能需要升级到【${editionDisplay}】`;
}