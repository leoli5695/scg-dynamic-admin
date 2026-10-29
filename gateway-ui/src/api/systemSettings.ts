import httpClient, { RequestOptions } from './http';

/** Console-wide feature switches (single row on the backend). */
export interface SystemSettings {
  stressTestEnabled: boolean;
  middlewareEnabled: boolean;
  pluginsEnabled: boolean;
  kubernetesEnabled: boolean;
  updatedBy?: string;
  updatedAt?: string;
}

export const getSystemSettings = (options?: RequestOptions) =>
  httpClient.get<SystemSettings>('/api/system-settings', options);

export const updateSystemSettings = (patch: Partial<SystemSettings>, options?: RequestOptions) =>
  httpClient.put<SystemSettings>('/api/system-settings', patch, options);