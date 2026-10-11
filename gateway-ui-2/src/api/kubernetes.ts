/**
 * Kubernetes API client (gateway-admin KubernetesController, /api/kubernetes).
 * All endpoints return the ApiResponse envelope.
 */
import { httpClient } from '@/api/http';
import type {
  ClusterImageInfo,
  ConnectionTestResult,
  DeployGatewayRequest,
  DeployGatewayResult,
  DeploymentStatusInfo,
  ImportClusterRequest,
  KubernetesCluster,
  KubernetesDeploymentInfo,
  KubernetesNodeInfo,
  KubernetesPodDetail,
  KubernetesPodInfo,
  UpdateClusterRequest,
} from '@/types/kubernetes';

/** Validate a kubeconfig without persisting it. */
export function testKubeconfigConnection(kubeconfig: string): Promise<ConnectionTestResult> {
  return httpClient.post<ConnectionTestResult>('/api/kubernetes/test-connection', { kubeconfig });
}

/** Import a cluster from kubeconfig content. */
export function importCluster(request: ImportClusterRequest): Promise<KubernetesCluster> {
  return httpClient.post<KubernetesCluster>('/api/kubernetes/clusters', request);
}

/** List all imported clusters. */
export function getClusters(options?: { signal?: AbortSignal }): Promise<KubernetesCluster[]> {
  return httpClient.get<KubernetesCluster[]>('/api/kubernetes/clusters', options);
}

/** Get a single cluster. */
export function getCluster(id: number): Promise<KubernetesCluster> {
  return httpClient.get<KubernetesCluster>(`/api/kubernetes/clusters/${id}`);
}

/** Update cluster name / description / enabled flag. */
export function updateCluster(id: number, request: UpdateClusterRequest): Promise<KubernetesCluster> {
  return httpClient.put<KubernetesCluster>(`/api/kubernetes/clusters/${id}`, request);
}

/** Delete a cluster (also evicts its cached API client). */
export function deleteCluster(id: number): Promise<void> {
  return httpClient.delete<void>(`/api/kubernetes/clusters/${id}`);
}

/** Re-verify connection and refresh cluster stats. */
export function refreshClusterConnection(id: number): Promise<KubernetesCluster> {
  return httpClient.post<KubernetesCluster>(`/api/kubernetes/clusters/${id}/refresh`);
}

/** List cluster nodes. */
export function getClusterNodes(id: number): Promise<KubernetesNodeInfo[]> {
  return httpClient.get<KubernetesNodeInfo[]>(`/api/kubernetes/clusters/${id}/nodes`);
}

/** List cluster namespace names. */
export function getClusterNamespaces(id: number): Promise<string[]> {
  return httpClient.get<string[]>(`/api/kubernetes/clusters/${id}/namespaces`);
}

/** List deployments; empty/omitted namespace means all namespaces. */
export function getClusterDeployments(id: number, namespace?: string): Promise<KubernetesDeploymentInfo[]> {
  return httpClient.get<KubernetesDeploymentInfo[]>(`/api/kubernetes/clusters/${id}/deployments`, {
    params: namespace ? { namespace } : {},
  });
}

/** List pods; empty/omitted namespace means all namespaces. */
export function getClusterPods(id: number, namespace?: string): Promise<KubernetesPodInfo[]> {
  return httpClient.get<KubernetesPodInfo[]>(`/api/kubernetes/clusters/${id}/pods`, {
    params: namespace ? { namespace } : {},
  });
}

/** Full pod detail (spec + statuses + conditions + events). */
export function getPodDetail(id: number, namespace: string, podName: string): Promise<KubernetesPodDetail> {
  return httpClient.get<KubernetesPodDetail>(`/api/kubernetes/clusters/${id}/pods/${namespace}/${podName}`);
}

/** Raw pod YAML definition. */
export function getPodYaml(id: number, namespace: string, podName: string): Promise<string> {
  return httpClient.get<string>(`/api/kubernetes/clusters/${id}/pods/${namespace}/${podName}/yaml`);
}

/** Pod logs; sinceSeconds takes priority over tailLines when provided. */
export function getPodLogs(
  id: number,
  namespace: string,
  podName: string,
  options?: { container?: string; tailLines?: number; sinceSeconds?: number },
): Promise<string> {
  return httpClient.get<string>(`/api/kubernetes/clusters/${id}/pods/${namespace}/${podName}/logs`, {
    params: { ...options },
  });
}

/** Deploy a gateway Deployment + Service into the cluster. */
export function deployGateway(id: number, request: DeployGatewayRequest): Promise<DeployGatewayResult> {
  return httpClient.post<DeployGatewayResult>(`/api/kubernetes/clusters/${id}/deploy`, request);
}

/** Delete a deployment and its companion service. */
export function deleteDeployment(id: number, namespace: string, deploymentName: string): Promise<void> {
  return httpClient.delete<void>(`/api/kubernetes/clusters/${id}/deployments/${namespace}/${deploymentName}`);
}

/** Scale a deployment to the given replica count. */
export function scaleDeployment(id: number, namespace: string, deploymentName: string, replicas: number): Promise<void> {
  return httpClient.put<void>(
    `/api/kubernetes/clusters/${id}/deployments/${namespace}/${deploymentName}/scale`,
    { replicas },
  );
}

/** Deployment status; returns { status: 'NOT_FOUND' } shortcut when missing. */
export function getDeploymentStatus(id: number, namespace: string, deploymentName: string): Promise<DeploymentStatusInfo> {
  return httpClient.get<DeploymentStatusInfo>(
    `/api/kubernetes/clusters/${id}/deployments/${namespace}/${deploymentName}/status`,
  );
}

/** List container images found in the cluster. */
export function getClusterImages(id: number, options?: { signal?: AbortSignal }): Promise<ClusterImageInfo[]> {
  return httpClient.get<ClusterImageInfo[]>(`/api/kubernetes/clusters/${id}/images`, options);
}
