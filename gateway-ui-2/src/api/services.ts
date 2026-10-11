/**
 * Service API.
 * Endpoints aligned with controller/ServiceController.java (/api/services).
 */
import httpClient, { RequestOptions } from '@/api/http';
import type {
  NacosDiscoveryServiceInfo,
  ServiceDefinition,
  ServiceInstanceItem,
  ServiceInstanceStatusUpdate,
} from '@/types/service';

const BASE = '/api/services';

/** GET /api/services — list services with injected instance health. */
export const listServices = (
  params?: { instanceId?: string; category?: string },
  options?: RequestOptions,
) => httpClient.get<ServiceDefinition[]>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/services/{name} */
export const getServiceByName = (name: string, options?: RequestOptions) =>
  httpClient.get<ServiceDefinition>(`${BASE}/${encodeURIComponent(name)}`, options);

/** GET /api/services/{name}/usage — routes referencing the service. */
export const checkServiceUsage = (name: string, options?: RequestOptions) =>
  httpClient.get<string[]>(`${BASE}/${encodeURIComponent(name)}/usage`, options);

/** POST /api/services — register a service. */
export const createService = (
  body: ServiceDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.post<ServiceDefinition>(BASE, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** PUT /api/services/{name} — update a service. */
export const updateService = (
  name: string,
  body: ServiceDefinition,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.put<ServiceDefinition>(`${BASE}/${encodeURIComponent(name)}`, body, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** DELETE /api/services/{name} */
export const deleteService = (
  name: string,
  instanceId?: string,
  options?: RequestOptions,
) => httpClient.delete<void>(`${BASE}/${encodeURIComponent(name)}`, {
  ...options,
  params: instanceId ? { instanceId } : undefined,
});

/** POST /api/services/{name}/instances — add one upstream instance. */
export const addServiceInstance = (
  name: string,
  instance: ServiceInstanceItem,
  gatewayInstanceId?: string,
  options?: RequestOptions,
) => httpClient.post<ServiceDefinition>(
  `${BASE}/${encodeURIComponent(name)}/instances`,
  instance,
  { ...options, params: gatewayInstanceId ? { instanceId: gatewayInstanceId } : undefined },
);

/** DELETE /api/services/{name}/instances/{instanceId} */
export const removeServiceInstance = (
  name: string,
  instanceId: string,
  gatewayInstanceId?: string,
  options?: RequestOptions,
) => httpClient.delete<ServiceDefinition>(
  `${BASE}/${encodeURIComponent(name)}/instances/${encodeURIComponent(instanceId)}`,
  { ...options, params: gatewayInstanceId ? { gatewayInstanceId } : undefined },
);

/** PUT /api/services/{name}/instances/{instanceId}/status */
export const updateServiceInstanceStatus = (
  name: string,
  instanceId: string,
  body: ServiceInstanceStatusUpdate,
  gatewayInstanceId?: string,
  options?: RequestOptions,
) => httpClient.put<ServiceDefinition>(
  `${BASE}/${encodeURIComponent(name)}/instances/${encodeURIComponent(instanceId)}/status`,
  body,
  { ...options, params: gatewayInstanceId ? { gatewayInstanceId } : undefined },
);

/** GET /api/services/nacos-discovery — services registered in Nacos. */
export const getNacosDiscoveryServices = (options?: RequestOptions) =>
  httpClient.get<NacosDiscoveryServiceInfo[]>(`${BASE}/nacos-discovery`, options);

/** POST /api/services/sync-to-nacos — sync all services to Nacos. */
export const syncServicesToNacos = (options?: RequestOptions) =>
  httpClient.post<Record<string, unknown>>(`${BASE}/sync-to-nacos`, undefined, options);
