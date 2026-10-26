/**
 * Gateway instance domain types.
 *
 * Field-aligned with backend sources of truth:
 * - model/GatewayInstanceEntity.java (GET responses; effectiveAccessUrl and
 *   effectiveNacosServerAddr come from Jackson-serialized getters)
 * - dto/InstanceCreateRequest.java (POST /api/instances body)
 * - dto/ScaleInstanceRequest.java (PUT /api/instances/{id}/scale body)
 */

/** Instance status codes (backend column status_code). */
export const INSTANCE_STATUS = {
  STARTING: 0,
  RUNNING: 1,
  ERROR: 2,
  STOPPING: 3,
  STOPPED: 4,
} as const;

export type InstanceStatusCode = (typeof INSTANCE_STATUS)[keyof typeof INSTANCE_STATUS];

/** Gateway instance as returned by GatewayInstanceController. */
export interface GatewayInstance {
  id: number;
  /** UUID, unique identifier. */
  instanceId: string;
  instanceName: string;
  clusterId?: number;
  clusterName?: string;
  namespace: string;
  nacosNamespace?: string;
  nacosServerAddr?: string;
  redisServerAddr?: string;
  jaegerServerAddr?: string;
  prometheusServerAddr?: string;
  /** small/medium/large/xlarge/custom */
  specType?: string;
  cpuCores?: number;
  memoryMB?: number;
  replicas?: number;
  image?: string;
  /** Legacy status string, kept for backward compatibility. */
  status?: string;
  /** 0-starting, 1-running, 2-error, 3-stopping, 4-stopped */
  statusCode?: InstanceStatusCode;
  lastHeartbeatTime?: string;
  missedHeartbeats?: number;
  statusMessage?: string;
  cpuUsagePercent?: number;
  memoryUsageMb?: number;
  requestsPerSecond?: number;
  activeConnections?: number;
  totalRequests?: number;
  errorRatePercent?: number;
  avgResponseTimeMs?: number;
  deploymentName?: string;
  serviceName?: string;
  /** NodePort | LoadBalancer | ClusterIP */
  serviceType?: string;
  nodePort?: number;
  nodeIp?: string;
  serverPort?: number;
  managementPort?: number;
  manualAccessUrl?: string;
  discoveredAccessUrl?: string;
  reportedAccessUrl?: string;
  /** Computed by backend getter getEffectiveAccessUrl(). */
  effectiveAccessUrl?: string;
  /** Computed by backend getter getEffectiveNacosServerAddr(). */
  effectiveNacosServerAddr?: string;
  enabled?: boolean;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** POST /api/instances request body (dto/InstanceCreateRequest.java). */
export interface InstanceCreateRequest {
  instanceName: string;
  /** Omit to register an externally managed instance (no Kubernetes deployment). */
  clusterId?: number;
  /** Required for a Kubernetes instance; derived as external-{instanceId} when omitted. */
  namespace?: string;
  createNamespace?: boolean;
  /** Defaults to "medium" on the backend. */
  specType?: string;
  cpuCores?: number;
  memoryMB?: number;
  replicas?: number;
  image?: string;
  /** "preset" or "custom". */
  imageType?: string;
  /** Always | IfNotPresent | Never. */
  imagePullPolicy?: string;
  description?: string;
  serverPort?: number;
  managementPort?: number;
  nacosServerAddr?: string;
  redisServerAddr?: string;
  jaegerServerAddr?: string;
  prometheusServerAddr?: string;
}

/** PUT /api/instances/{id}/scale request body (dto/ScaleInstanceRequest.java). */
export interface ScaleInstanceRequest {
  /** 1-10; null/omitted keeps current replicas. */
  replicas?: number;
  /** small/medium/large/custom; null/omitted keeps current spec. */
  specType?: string;
  /** Only used when specType is "custom". */
  cpuCores?: number;
  /** Only used when specType is "custom". */
  memoryMB?: number;
  /** Optional reason for audit log. */
  reason?: string;
}

/** GET /api/instances/specs item (GatewayInstanceService#getAvailableSpecs). */
export interface InstanceSpecOption {
  /** small/medium/large/xlarge/custom */
  type: string;
  cpuCores: number;
  memoryMB: number;
  description?: string;
}

/** Kubernetes event item (GET /api/instances/{id}/events → events list). */
export interface K8sEvent {
  type?: string;
  reason?: string;
  message?: string;
  count?: number;
  firstTimestamp?: string | null;
  lastTimestamp?: string | null;
  objectName?: string;
  objectKind?: string;
  namespace?: string;
}

/** GET /api/instances/{id}/events payload (backend Map<String,Object>). */
export interface InstanceEventsPayload {
  events?: K8sEvent[];
  total?: number;
  warningCount?: number;
  normalCount?: number;
  [key: string]: unknown;
}

/** Container status inside a gateway pod (GatewayInstanceService#getInstancePods). */
export interface PodContainerStatus {
  name?: string;
  ready?: boolean;
  restartCount?: number;
  image?: string;
}

/** GET /api/instances/{id}/pods item (GatewayInstanceService#getInstancePods). */
export interface InstancePod {
  name?: string;
  namespace?: string;
  /** Running | Pending | Failed | ... (K8s pod phase) */
  phase?: string;
  podIP?: string;
  startTime?: string;
  /** Management/actuator port used to build the Prometheus instance label. */
  managementPort?: number;
  containers?: PodContainerStatus[];
}

/** GET /api/instances/{id}/health-score payload (ClusterHealthService#getClusterHealth). */
export interface ClusterHealth {
  score?: number;
  level?: string;
  clusterName?: string;
  namespace?: string;
  nodeHealth?: {
    readyNodes?: number;
    totalNodes?: number;
    score?: number;
  };
  podHealth?: {
    runningPods?: number;
    totalPods?: number;
    score?: number;
    totalRestarts?: number;
    problematicPods?: Array<{ name?: string; restartCount?: number }>;
  };
  issues?: Array<{ severity?: string; message?: string }>;
  recommendations?: string[];
  [key: string]: unknown;
}

/** GET /api/instances/{id}/resource-metrics payload (ClusterHealthService#getResourceMetrics). */
export interface InstanceResourceMetrics {
  cpuUsage?: number;
  memoryUsage?: number;
  networkIn?: number;
  networkOut?: number;
  timestamp?: number;
  mode?: string;
  nodes?: {
    total?: number;
    list?: Array<{
      name?: string;
      ready?: boolean;
      allocatable?: { cpu?: string; memory?: string };
      [key: string]: unknown;
    }>;
  };
  pods?: {
    total?: number;
    running?: number;
    list?: Array<{
      name?: string;
      phase?: string;
      podIP?: string;
      containers?: unknown[];
      [key: string]: unknown;
    }>;
  };
  [key: string]: unknown;
}
