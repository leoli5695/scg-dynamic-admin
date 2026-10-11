/**
 * Kubernetes domain types, aligned with gateway-admin backend:
 * - entity:  com.leoli.gateway.admin.model.KubernetesCluster
 * - services: KubernetesService / KubernetesResourceService /
 *             DeploymentService / ClusterConnectionService map payloads
 */

export type KubernetesConnectionStatus =
  | 'CONNECTED'
  | 'IMPORTED'
  | 'CONNECTION_FAILED'
  | 'ERROR'
  | 'UNKNOWN';

/** Persisted cluster record (kubernetes_clusters table). */
export interface KubernetesCluster {
  id: number;
  clusterName: string;
  serverUrl?: string;
  /** Kubeconfig content (YAML). */
  kubeconfig?: string;
  contextName?: string;
  clusterVersion?: string;
  nodeCount?: number;
  podCount?: number;
  namespaceCount?: number;
  totalCpuCores?: number;
  totalMemoryGb?: number;
  connectionStatus: string;
  lastCheckedAt?: string;
  description?: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** POST /clusters request body. */
export interface ImportClusterRequest {
  clusterName: string;
  kubeconfig: string;
  description?: string;
}

/** PUT /clusters/{id} request body (all optional). */
export interface UpdateClusterRequest {
  clusterName?: string;
  description?: string;
  enabled?: boolean;
}

/** POST /test-connection result map. */
export interface ConnectionTestResult {
  success: boolean;
  serverUrl?: string;
  contextName?: string;
  clusterVersion?: string;
  nodeCount?: number;
  namespaceCount?: number;
  error?: string;
  errorCode?: number;
}

/** GET /clusters/{id}/nodes item. */
export interface KubernetesNodeInfo {
  name: string;
  /** Ready condition status: "True" / "False" / "Unknown". */
  status?: string;
  kubeletVersion?: string;
  osImage?: string;
}

/** GET /clusters/{id}/pods item. */
export interface KubernetesPodInfo {
  name: string;
  namespace: string;
  phase: string;
  podIP?: string;
  labels?: Record<string, string>;
  containers?: Array<{ name: string; ready: boolean; restartCount: number }>;
}

/** Container spec entry of a pod detail. */
export interface PodDetailContainer {
  name: string;
  image: string;
  imagePullPolicy?: string;
  ports?: Array<{ containerPort: number; protocol: string; name?: string }>;
  env?: Array<{ name: string; value?: string }>;
  resources?: {
    requests?: Record<string, string>;
    limits?: Record<string, string>;
  };
}

/** Container status entry of a pod detail. */
export interface PodContainerStatus {
  name: string;
  ready: boolean;
  restartCount: number;
  image?: string;
  state?: {
    running?: { startedAt?: string };
    waiting?: { reason?: string; message?: string };
    terminated?: { exitCode?: number; reason?: string; finishedAt?: string; startedAt?: string };
  };
}

/** Pod condition entry. */
export interface PodCondition {
  type: string;
  status: string;
  reason?: string;
  message?: string;
  lastTransitionTime?: string;
}

/** Pod event entry. */
export interface PodEvent {
  type: string;
  reason: string;
  message: string;
  count?: number;
  firstTimestamp?: string;
  lastTimestamp?: string;
}

/** GET /clusters/{id}/pods/{namespace}/{name} result. */
export interface KubernetesPodDetail {
  name: string;
  namespace: string;
  phase: string;
  podIP?: string;
  hostIP?: string;
  startTime?: string;
  labels?: Record<string, string>;
  annotations?: Record<string, string>;
  nodeName?: string;
  serviceAccountName?: string;
  restartPolicy?: string;
  dnsPolicy?: string;
  containers?: PodDetailContainer[];
  containerStatuses?: PodContainerStatus[];
  conditions?: PodCondition[];
  events?: PodEvent[];
}

/** GET /clusters/{id}/deployments item. */
export interface KubernetesDeploymentInfo {
  name: string;
  namespace: string;
  replicas: number;
  readyReplicas?: number;
  availableReplicas?: number;
  labels?: Record<string, string>;
  createdAt?: string;
}

/** POST /clusters/{id}/deploy request body. */
export interface DeployGatewayRequest {
  namespace?: string;
  appName?: string;
  replicas?: number;
  image?: string;
  envVars?: Record<string, string>;
  containerPort?: number;
}

/** POST /clusters/{id}/deploy result map. */
export interface DeployGatewayResult {
  deploymentName: string;
  serviceName: string;
  namespace: string;
  replicas: number;
  status: string;
}

/** GET /clusters/{id}/deployments/{ns}/{name}/status result. */
export interface DeploymentStatusInfo {
  name?: string;
  namespace?: string;
  replicas?: number;
  readyReplicas?: number;
  availableReplicas?: number;
  updatedReplicas?: number;
  conditions?: unknown[];
  /** "NOT_FOUND" shortcut when missing. */
  status?: string;
}

/** GET /clusters/{id}/images item. */
export interface ClusterImageInfo {
  name: string;
}
