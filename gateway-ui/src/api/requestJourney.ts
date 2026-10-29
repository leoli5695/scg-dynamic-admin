import httpClient, { type RequestOptions } from './http';
import type { RequestTrace } from '../types/trace';
import type { TokenUsageEvent } from './tokenUsage';

export interface RequestJourney {
  instanceId: string;
  traceId: string;
  trace: RequestTrace | null;
  accessLog?: RequestAccessLog | null;
  usage: TokenUsageEvent[];
}
export interface RequestAccessLog {
  id: number;
  instanceId: string;
  traceId: string;
  routeId: string | null;
  method: string;
  path: string;
  statusCode: number;
  durationMs: number;
  timestamp: string;
  errorMessage: string | null;
}
export const getRequestJourney = (instanceId: string, traceId: string, options?: RequestOptions) =>
  httpClient.get<RequestJourney>(`/api/request-journeys/${encodeURIComponent(traceId)}`,
    { ...options, params: { instanceId } });
