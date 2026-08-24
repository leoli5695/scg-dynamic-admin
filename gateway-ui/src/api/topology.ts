/**
 * Traffic topology API.
 * Endpoints aligned with controller/TrafficTopologyController.java
 * (/api/topology). `minutes` is clamped to 1..1440 by the backend.
 */
import httpClient, { RequestOptions } from './http';
import type { TopologyGraph, TrafficSummary } from '../types/topology';

const BASE = '/api/topology';

/** GET /api/topology/{instanceId} — full topology graph. */
export const getTopology = (
  instanceId: string,
  params: { minutes?: number },
  options?: RequestOptions,
) => httpClient.get<TopologyGraph>(`${BASE}/${instanceId}`, { ...options, params });

/** GET /api/topology/{instanceId}/summary — traffic summary. */
export const getTrafficSummary = (
  instanceId: string,
  params: { minutes?: number },
  options?: RequestOptions,
) => httpClient.get<TrafficSummary>(`${BASE}/${instanceId}/summary`, { ...options, params });
