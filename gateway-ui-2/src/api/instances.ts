/**
 * Gateway instance API.
 * Endpoints aligned with controller/GatewayInstanceController.java (/api/instances).
 */
import httpClient, { RequestOptions } from '@/api/http';
import type {
  ClusterHealth,
  GatewayInstance,
  InstanceCreateRequest,
  InstanceEventsPayload,
  InstancePod,
  InstanceResourceMetrics,
  InstanceSpecOption,
  ScaleInstanceRequest,
} from '@/types/instance';

const BASE = '/api/instances';

/** GET /api/instances/config — frontend-facing gateway-admin config. */
export const getInstanceConfig = (options?: RequestOptions) =>
  httpClient.get<{ nacosServerAddr?: string }>(`${BASE}/config`, options);

/** GET /api/instances — list all instances. */
export const listInstances = (options?: RequestOptions) =>
  httpClient.get<GatewayInstance[]>(BASE, options);

/** GET /api/instances/{id} */
export const getInstanceById = (id: number, options?: RequestOptions) =>
  httpClient.get<GatewayInstance>(`${BASE}/${id}`, options);

/** GET /api/instances/by-instance-id/{instanceId} (UUID lookup). */
export const getInstanceByInstanceId = (instanceId: string, options?: RequestOptions) =>
  httpClient.get<GatewayInstance>(`${BASE}/by-instance-id/${instanceId}`, options);

/** GET /api/instances/specs — available spec types. */
export const listInstanceSpecs = (options?: RequestOptions) =>
  httpClient.get<InstanceSpecOption[]>(`${BASE}/specs`, options);

/** POST /api/instances */
export const createInstance = (body: InstanceCreateRequest, options?: RequestOptions) =>
  httpClient.post<GatewayInstance>(BASE, body, options);

/** DELETE /api/instances/{id} */
export const deleteInstance = (id: number, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${id}`, options);

/** POST /api/instances/{id}/start */
export const startInstance = (id: number, options?: RequestOptions) =>
  httpClient.post<GatewayInstance>(`${BASE}/${id}/start`, undefined, options);

/** POST /api/instances/{id}/stop */
export const stopInstance = (id: number, options?: RequestOptions) =>
  httpClient.post<GatewayInstance>(`${BASE}/${id}/stop`, undefined, options);

/** GET /api/instances/{id}/pods */
export const getInstancePods = (id: number, options?: RequestOptions) =>
  httpClient.get<InstancePod[]>(`${BASE}/${id}/pods`, options);

/** POST /api/instances/{id}/refresh */
export const refreshInstanceStatus = (id: number, options?: RequestOptions) =>
  httpClient.post<GatewayInstance>(`${BASE}/${id}/refresh`, undefined, options);

/** PUT /api/instances/{id}/scale — replicas and/or spec in one call. */
export const scaleInstance = (id: number, body: ScaleInstanceRequest, options?: RequestOptions) =>
  httpClient.put<GatewayInstance>(`${BASE}/${id}/scale`, body, options);

/** PUT /api/instances/{id}/replicas — direct replica update, no restart. */
export const updateInstanceReplicas = (id: number, replicas: number, options?: RequestOptions) =>
  httpClient.put<GatewayInstance>(`${BASE}/${id}/replicas`, { replicas }, options);

/** PUT /api/instances/{id}/spec — CPU/memory update (requires restart). */
export const updateInstanceSpec = (
  id: number,
  body: { specType?: string; cpuCores?: number; memoryMB?: number },
  options?: RequestOptions,
) => httpClient.put<GatewayInstance>(`${BASE}/${id}/spec`, body, options);

/** PUT /api/instances/{id}/image — rolling image update. */
export const updateInstanceImage = (id: number, image: string, options?: RequestOptions) =>
  httpClient.put<GatewayInstance>(`${BASE}/${id}/image`, { image }, options);

/** GET /api/instances/{id}/events — K8s events for the instance pods. */
export const getInstanceEvents = (
  id: number,
  params?: { sinceSeconds?: number; limit?: number },
  options?: RequestOptions,
) => httpClient.get<InstanceEventsPayload>(`${BASE}/${id}/events`, { ...options, params: params as Record<string, unknown> });

/** GET /api/instances/{id}/health-score */
export const getInstanceHealthScore = (id: number, options?: RequestOptions) =>
  httpClient.get<ClusterHealth>(`${BASE}/${id}/health-score`, options);

/** GET /api/instances/{id}/resource-metrics */
export const getInstanceResourceMetrics = (id: number, options?: RequestOptions) =>
  httpClient.get<InstanceResourceMetrics>(`${BASE}/${id}/resource-metrics`, options);
