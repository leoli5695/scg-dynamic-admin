/**
 * Alert domain types, aligned with gateway-admin:
 * - controller/AlertController.java (/api/alerts)
 * - service/AlertConfigService.java (ThresholdConfig POJO, defaults)
 * - model/AlertHistory.java, model/AlertConfig.java
 *
 * Standard ApiResponse envelope, auto-unwrapped by httpClient.
 */

export interface CpuThreshold {
  processThreshold?: number;
  systemThreshold?: number;
  enabled?: boolean;
}

export interface MemoryThreshold {
  heapThreshold?: number;
  enabled?: boolean;
}

export interface HttpThreshold {
  errorRateThreshold?: number;
  responseTimeThreshold?: number;
  enabled?: boolean;
}

export interface InstanceThreshold {
  unhealthyThreshold?: number;
  enabled?: boolean;
}

export interface ThreadThreshold {
  activeThreshold?: number;
  enabled?: boolean;
}

/** Threshold config JSON shape (AlertConfigService.ThresholdConfig). */
export interface AlertThresholdConfig {
  cpu?: CpuThreshold;
  memory?: MemoryThreshold;
  http?: HttpThreshold;
  instance?: InstanceThreshold;
  thread?: ThreadThreshold;
}

/** GET /api/alerts/config data payload (backend Map<String,Object>). */
export interface AlertConfigData {
  id?: number;
  instanceId?: string;
  configName?: string;
  emailRecipients?: string[];
  emailLanguage?: string;
  thresholdConfig?: AlertThresholdConfig;
  enabled?: boolean;
}

/** POST/PUT /api/alerts/config request body. */
export interface AlertConfigSaveRequest {
  id?: number;
  instanceId?: string;
  configName?: string;
  emailRecipients?: string[];
  emailLanguage?: string;
  enabled?: boolean;
  thresholdConfig?: AlertThresholdConfig;
}

/** POST /api/alerts/config data payload. */
export interface AlertConfigSaveResult {
  id?: number;
}

/**
 * Alert history row (model/AlertHistory serialized by Jackson).
 * alertType: CPU | MEMORY | HTTP_ERROR | RESPONSE_TIME | INSTANCE | THREAD
 * alertLevel: INFO | WARNING | ERROR | CRITICAL
 * status: SENT | FAILED
 */
export interface AlertHistoryItem {
  id?: number;
  instanceId?: string;
  alertType?: string;
  alertLevel?: string;
  metricName?: string;
  metricValue?: number;
  thresholdValue?: number;
  title?: string;
  content?: string;
  emailRecipients?: string;
  status?: string;
  errorMessage?: string;
  createdAt?: string;
}

/** GET /api/alerts/history data payload (Spring Page projection). */
export interface AlertHistoryPage {
  content?: AlertHistoryItem[];
  totalElements?: number;
  totalPages?: number;
  page?: number;
  size?: number;
}

/** GET /api/alerts/thresholds/metrics data payload. */
export interface AlertThresholdMetrics {
  processCpuUsage?: number;
  systemCpuUsage?: number;
  heapUsagePercent?: number;
  errorRate?: number;
  avgResponseTimeMs?: number;
  unhealthyInstances?: number;
  totalInstances?: number;
  liveThreads?: number;
  peakThreads?: number;
  threadUsagePercent?: number;
}

/** POST /api/alerts/test request body. */
export interface SendTestEmailRequest {
  recipients: string[];
  language?: string;
}
