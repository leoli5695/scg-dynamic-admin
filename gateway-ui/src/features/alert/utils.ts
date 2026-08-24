import type { AlertThresholdConfig } from '../../types/alert';

/** Defaults mirror AlertConfigService#getDefaultThresholdConfig. */
export const DEFAULT_THRESHOLD_CONFIG: AlertThresholdConfig = {
  cpu: { processThreshold: 80, systemThreshold: 90, enabled: true },
  memory: { heapThreshold: 85, enabled: true },
  http: { errorRateThreshold: 5, responseTimeThreshold: 2000, enabled: true },
  instance: { unhealthyThreshold: 1, enabled: true },
  thread: { activeThreshold: 90, enabled: true },
};

/** Merge a (possibly partial) server config over the defaults. */
export const mergeThresholdConfig = (server?: AlertThresholdConfig): AlertThresholdConfig => ({
  cpu: { ...DEFAULT_THRESHOLD_CONFIG.cpu, ...server?.cpu },
  memory: { ...DEFAULT_THRESHOLD_CONFIG.memory, ...server?.memory },
  http: { ...DEFAULT_THRESHOLD_CONFIG.http, ...server?.http },
  instance: { ...DEFAULT_THRESHOLD_CONFIG.instance, ...server?.instance },
  thread: { ...DEFAULT_THRESHOLD_CONFIG.thread, ...server?.thread },
});

/** Antd Tag class per alert level (styled in App.premium.css). */
export const getLevelTagClass = (level?: string) => {
  switch (level) {
    case 'WARNING': return 'warning-tag';
    case 'CRITICAL': return 'critical-tag';
    case 'ERROR': return 'error-tag';
    default: return 'info-tag';
  }
};

/** Same pattern the backend validates with (AlertConfigService#EMAIL_PATTERN). */
export const EMAIL_PATTERN = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
